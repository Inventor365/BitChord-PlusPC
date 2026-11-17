#!/usr/bin/env python3
"""
Standalone structural & cryptographic verifier for Sonara macOS artifacts:
  - .app bundles & .zip archives (Info.plist, Mach-O CPU arch, Sonara.cfg &
    classpath JARs, _CodeSignature/CodeResources SHA-256 digests, Mach-O
    LC_CODE_SIGNATURE CodeDirectory Special Slots -1 and -3 + code page slots;
    plus codesign/spctl on macOS hosts)
  - .dmg disk images (UDIF koly trailer, plist XML, blkx mish descriptors,
    BLKX_RUN_ZLIB <= 2048 sector enforcement, mish CRC-32 across non-zero-fill
    runs, Master CRC-32 at koly[352..364], Data Fork CRC-32 at koly[80..92],
    fsck.hfsplus -fn when available, embedded HFS+ Sonara.app + /Applications
    symlink check, plus hdiutil verify on macOS hosts)
  - .pkg flat installers (XAR header, raw zlib 0x78 0xda TOC stream, TOC &
    per-<file> archived/extracted SHA-1 checksums, Bom BOMStore header + path
    entries + POSIX cksum verification, PackageInfo version, stripped-header
    gzip 1f 8b 08 00 00 00 00 00 Payload + bsdtar -tzf + cpio Sonara.app
    verification, plus pkgutil --check-signature on macOS hosts)

Runs identically on Linux CI and macOS CI using only the Python 3 standard library.
"""

from __future__ import annotations

import argparse
import bz2
import gzip
import hashlib
import json
import os
import plistlib
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
import zlib
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

# Mach-O constants
MH_MAGIC_64 = 0xFEEDFACF
MH_CIGAM_64 = 0xCFFAEDFE
FAT_MAGIC = 0xCAFEBABE
FAT_CIGAM = 0xBEBAFECA
FAT_MAGIC_64 = 0xCAFEBABF
FAT_CIGAM_64 = 0xBFBAFECA
CPU_TYPE_X86_64 = 0x01000007
CPU_TYPE_ARM64 = 0x0100000C
LC_CODE_SIGNATURE = 0x1D

# Apple Code Signing constants (big-endian)
CSMAGIC_EMBEDDED_SIGNATURE = 0xFADE0CC0
CSMAGIC_CODEDIRECTORY = 0xFADE0C02
CSMAGIC_BLOBWRAPPER = 0xFADE0B01
CSSLOT_CODEDIRECTORY = 0
CSSLOT_ALT_CODEDIRECTORY_BASE = 0x1000
CSSLOT_ALT_CODEDIRECTORY_MAX = 0x100F
CSSLOT_SIGNATURESLOT = 0x10000
CS_ADHOC = 0x00000002

CS_HASHTYPE_SHA1 = 1
CS_HASHTYPE_SHA256 = 2
CS_HASHTYPE_SHA256_TRUNCATED = 3
CS_HASHTYPE_SHA384 = 4

# UDIF (.dmg) constants
KOLY_MAGIC = b"koly"
MISH_MAGIC = b"mish"
UDIF_CHECKSUM_CRC32 = 2
BLKX_RUN_ZERO = 0x00000000
BLKX_RUN_RAW = 0x00000001
BLKX_RUN_IGNORE = 0x00000002
BLKX_RUN_ADC = 0x80000004
BLKX_RUN_ZLIB = 0x80000005
BLKX_RUN_BZ2 = 0x80000006
BLKX_RUN_COMMENT = 0x7FFFFFFE
BLKX_RUN_TERM = 0xFFFFFFFF
MAX_ZLIB_RUN_SECTORS = 2048
SECTOR_SIZE = 512

# XAR / BOM / Payload constants
XAR_MAGIC = b"xar!"
BOM_MAGIC = b"BOMStore"
STRIPPED_GZIP_PREFIX = bytes.fromhex("1f 8b 08 00 00 00 00 00")

REPO_ROOT = Path(__file__).resolve().parents[2]


class VerificationError(Exception):
    """Raised when any structural or cryptographic check fails."""


def _build_posix_crc_table() -> List[int]:
    table: List[int] = []
    for i in range(256):
        crc = i << 24
        for _ in range(8):
            if crc & 0x80000000:
                crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF
            else:
                crc = (crc << 1) & 0xFFFFFFFF
        table.append(crc)
    return table


POSIX_CRC_TABLE = _build_posix_crc_table()


def posix_cksum(data: bytes) -> int:
    crc = 0
    table = POSIX_CRC_TABLE
    for b in data:
        crc = (((crc << 8) & 0xFFFFFFFF) ^ table[((crc >> 24) ^ b) & 0xFF])
    n = len(data)
    while n > 0:
        crc = (((crc << 8) & 0xFFFFFFFF) ^ table[((crc >> 24) ^ (n & 0xFF)) & 0xFF])
        n >>= 8
    return (~crc) & 0xFFFFFFFF


def find_host_tool(names: Sequence[str]) -> Optional[str]:
    extra_dirs = [
        REPO_ROOT / "build" / "host-tools" / "bin",
        Path("/sbin"),
        Path("/usr/sbin"),
        Path("/usr/local/bin"),
        Path("/opt/homebrew/bin"),
    ]
    for name in names:
        found = shutil.which(name)
        if found:
            return found
        for d in extra_dirs:
            candidate = d / name
            if candidate.is_file() and os.access(candidate, os.X_OK):
                return str(candidate)
    return None


def infer_arch_from_name(path: Path) -> Optional[str]:
    lower = path.name.lower()
    if "arm64" in lower or "aarch64" in lower:
        return "arm64"
    if "x64" in lower or "x86_64" in lower or "amd64" in lower:
        return "x64"
    return None


def version_matches(actual: str, expected: str) -> bool:
    act = actual.strip().lstrip("v")
    exp = expected.strip().lstrip("v")
    if act == exp:
        return True
    # Compose Desktop nativePackageVersion maps MAJOR.MINOR[.PATCH] -> MAJOR.MINOR.<buildCode>
    exp_base = exp.split("-")[0]
    exp_parts = exp_base.split(".")
    act_parts = act.split("-")[0].split(".")
    if len(exp_parts) >= 2 and len(act_parts) == 3 and act_parts[:2] == exp_parts[:2] and act_parts[2].isdigit():
        return True
    return False


def extract_zip_preserving_symlinks(zip_path: Path, dest_dir: Path) -> None:
    dest_root = dest_dir.resolve()
    with zipfile.ZipFile(zip_path, "r") as zf:
        for info in zf.infolist():
            rel = info.filename
            if not rel:
                continue
            target = (dest_root / rel).resolve() if False else (dest_root / os.path.normpath(rel))
            # Prevent path traversal without resolving final symlink component
            parent_resolved = target.parent.resolve()
            if parent_resolved != dest_root and dest_root not in parent_resolved.parents:
                raise VerificationError(f"Unsafe path in zip archive: {rel}")

            unix_mode = (info.external_attr >> 16) & 0xFFFF
            file_type = unix_mode & 0o170000

            if info.is_dir() or file_type == stat.S_IFDIR:
                target.mkdir(parents=True, exist_ok=True)
                continue

            target.parent.mkdir(parents=True, exist_ok=True)
            if file_type == stat.S_IFLNK:
                link_target = zf.read(info).decode("utf-8")
                if target.exists() or target.is_symlink():
                    target.unlink()
                os.symlink(link_target, target)
            else:
                with zf.open(info, "r") as src, open(target, "wb") as dst:
                    shutil.copyfileobj(src, dst)
                perms = unix_mode & 0o7777
                if perms:
                    os.chmod(target, perms)


def _cd_hash_bytes(data: bytes, hash_type: int, hash_size: int) -> bytes:
    if hash_type == CS_HASHTYPE_SHA1:
        digest = hashlib.sha1(data).digest()
    elif hash_type in (CS_HASHTYPE_SHA256, CS_HASHTYPE_SHA256_TRUNCATED):
        digest = hashlib.sha256(data).digest()
    elif hash_type == CS_HASHTYPE_SHA384:
        digest = hashlib.sha384(data).digest()
    else:
        raise VerificationError(f"Unsupported CodeDirectory hashType: {hash_type}")
    return digest[:hash_size]


def _extract_macho_slice(binary: bytes, expected_arch: Optional[str]) -> Tuple[bytes, int]:
    if len(binary) < 32:
        raise VerificationError("Mach-O binary is truncated (< 32 bytes)")

    magic_be = struct.unpack(">I", binary[:4])[0]
    magic_le = struct.unpack("<I", binary[:4])[0]

    if magic_le == MH_MAGIC_64:
        cputype = struct.unpack("<I", binary[4:8])[0]
        return binary, cputype
    if magic_be == MH_CIGAM_64:
        cputype = struct.unpack(">I", binary[4:8])[0]
        return binary, cputype

    if magic_be in (FAT_MAGIC, FAT_MAGIC_64):
        nfat = struct.unpack(">I", binary[4:8])[0]
        slices: List[Tuple[int, bytes]] = []
        pos = 8
        for _ in range(nfat):
            if magic_be == FAT_MAGIC:
                cputype, _, offset, size, _ = struct.unpack(">IIIII", binary[pos : pos + 20])
                pos += 20
            else:
                cputype, _, offset, size, _, _ = struct.unpack(">IIQQII", binary[pos : pos + 32])
                pos += 32
            slices.append((cputype, binary[offset : offset + size]))
        if expected_arch == "arm64":
            for cpu, sl in slices:
                if cpu == CPU_TYPE_ARM64:
                    return sl, cpu
        elif expected_arch == "x64":
            for cpu, sl in slices:
                if cpu == CPU_TYPE_X86_64:
                    return sl, cpu
        if slices:
            return slices[0][1], slices[0][0]

    raise VerificationError(f"Invalid Mach-O magic: 0x{magic_be:08x}")


def verify_macho_and_codesign(
    exec_path: Path,
    info_plist_bytes: bytes,
    code_resources_bytes: bytes,
    expected_arch: Optional[str],
    require_developer_id: bool = False,
) -> Dict[str, Any]:
    raw = exec_path.read_bytes()
    macho, cputype = _extract_macho_slice(raw, expected_arch)

    arch_map = {CPU_TYPE_ARM64: "arm64", CPU_TYPE_X86_64: "x64"}
    actual_arch = arch_map.get(cputype, f"unknown(0x{cputype:08x})")
    if expected_arch and actual_arch != expected_arch:
        raise VerificationError(
            f"{exec_path}: Mach-O CPU architecture mismatch: expected {expected_arch}, got {actual_arch} (0x{cputype:08x})"
        )
    if cputype not in arch_map:
        raise VerificationError(f"{exec_path}: unexpected Mach-O cputype 0x{cputype:08x}")

    magic, _, _, _, ncmds, sizeofcmds, _, _ = struct.unpack("<IIIIIIII", macho[:32])
    if magic != MH_MAGIC_64:
        raise VerificationError(f"{exec_path}: expected 64-bit little-endian Mach-O header")
    if 32 + sizeofcmds > len(macho):
        raise VerificationError(f"{exec_path}: load commands exceed Mach-O size")

    sig_off: Optional[int] = None
    sig_size: Optional[int] = None
    pos = 32
    for _ in range(ncmds):
        cmd, cmdsize = struct.unpack("<II", macho[pos : pos + 8])
        if cmdsize < 8 or pos + cmdsize > 32 + sizeofcmds:
            raise VerificationError(f"{exec_path}: malformed Mach-O load command at offset {pos}")
        if cmd == LC_CODE_SIGNATURE:
            if cmdsize < 16:
                raise VerificationError(f"{exec_path}: truncated LC_CODE_SIGNATURE command")
            _, _, sig_off, sig_size = struct.unpack("<IIII", macho[pos : pos + 16])
        pos += cmdsize

    if sig_off is None or sig_size is None:
        raise VerificationError(f"{exec_path}: missing LC_CODE_SIGNATURE load command")
    if sig_off + sig_size > len(macho):
        raise VerificationError(f"{exec_path}: LC_CODE_SIGNATURE ({sig_off}+{sig_size}) out of bounds ({len(macho)})")

    superblob = macho[sig_off : sig_off + sig_size]
    if len(superblob) < 12:
        raise VerificationError(f"{exec_path}: LC_CODE_SIGNATURE SuperBlob too small")
    sb_magic, sb_len, sb_count = struct.unpack(">III", superblob[:12])
    if sb_magic != CSMAGIC_EMBEDDED_SIGNATURE:
        raise VerificationError(
            f"{exec_path}: invalid CS_SuperBlob magic 0x{sb_magic:08x} (expected 0x{CSMAGIC_EMBEDDED_SIGNATURE:08x})"
        )
    if sb_len > len(superblob):
        raise VerificationError(f"{exec_path}: CS_SuperBlob length {sb_len} exceeds datasize {len(superblob)}")

    code_dirs: List[Tuple[int, bytes]] = []
    has_cms_signature = False
    idx_pos = 12
    for _ in range(sb_count):
        slot_type, blob_off = struct.unpack(">II", superblob[idx_pos : idx_pos + 8])
        idx_pos += 8
        if blob_off + 8 > sb_len:
            raise VerificationError(f"{exec_path}: blob slot 0x{slot_type:x} offset {blob_off} out of bounds")
        blob_magic, blob_len = struct.unpack(">II", superblob[blob_off : blob_off + 8])
        if blob_off + blob_len > sb_len:
            raise VerificationError(f"{exec_path}: blob slot 0x{slot_type:x} length {blob_len} out of bounds")
        blob = superblob[blob_off : blob_off + blob_len]
        if slot_type == CSSLOT_CODEDIRECTORY or (
            CSSLOT_ALT_CODEDIRECTORY_BASE <= slot_type <= CSSLOT_ALT_CODEDIRECTORY_MAX
        ):
            if blob_magic != CSMAGIC_CODEDIRECTORY:
                raise VerificationError(f"{exec_path}: invalid CodeDirectory magic 0x{blob_magic:08x}")
            code_dirs.append((slot_type, blob))
        elif slot_type == CSSLOT_SIGNATURESLOT and blob_magic == CSMAGIC_BLOBWRAPPER and blob_len > 8:
            has_cms_signature = True

    if not code_dirs:
        raise VerificationError(f"{exec_path}: no CS_CodeDirectory found in LC_CODE_SIGNATURE")

    is_adhoc = False
    verified_cds = 0
    for slot_type, cd in code_dirs:
        if len(cd) < 44:
            raise VerificationError(f"{exec_path}: truncated CS_CodeDirectory (len={len(cd)})")
        (
            _cd_magic,
            _cd_len,
            _cd_ver,
            cd_flags,
            hash_offset,
            ident_offset,
            n_special_slots,
            n_code_slots,
            code_limit,
            hash_size,
            hash_type,
            _platform,
            page_size_shift,
            _spare2,
        ) = struct.unpack(">9I4BI", cd[:44])

        if cd_flags & CS_ADHOC:
            is_adhoc = True

        ident_end = cd.find(b"\x00", ident_offset)
        ident = cd[ident_offset:ident_end].decode("utf-8", errors="replace") if ident_end != -1 else ""
        if not ident:
            raise VerificationError(f"{exec_path}: empty identifier in CodeDirectory")

        if n_special_slots < 3:
            raise VerificationError(
                f"{exec_path}: CodeDirectory slot 0x{slot_type:x} has nSpecialSlots={n_special_slots} (< 3; missing Info.plist / CodeResources slots)"
            )

        slot_minus_1_off = hash_offset - 1 * hash_size
        slot_minus_3_off = hash_offset - 3 * hash_size
        if slot_minus_3_off < 44 or hash_offset + n_code_slots * hash_size > len(cd):
            raise VerificationError(f"{exec_path}: CodeDirectory hash table offsets out of bounds")

        recorded_info_hash = cd[slot_minus_1_off : slot_minus_1_off + hash_size]
        expected_info_hash = _cd_hash_bytes(info_plist_bytes, hash_type, hash_size)
        if recorded_info_hash != expected_info_hash:
            raise VerificationError(
                f"{exec_path}: CodeDirectory Special Slot -1 (Info.plist) mismatch in slot 0x{slot_type:x}: "
                f"recorded={recorded_info_hash.hex()} != actual={expected_info_hash.hex()}"
            )

        recorded_res_hash = cd[slot_minus_3_off : slot_minus_3_off + hash_size]
        expected_res_hash = _cd_hash_bytes(code_resources_bytes, hash_type, hash_size)
        if recorded_res_hash != expected_res_hash:
            raise VerificationError(
                f"{exec_path}: CodeDirectory Special Slot -3 (CodeResources) mismatch in slot 0x{slot_type:x}: "
                f"recorded={recorded_res_hash.hex()} != actual={expected_res_hash.hex()}"
            )

        # Verify code page hashes (0 .. nCodeSlots - 1) against macho[0:code_limit]
        page_size = (1 << page_size_shift) if page_size_shift > 0 else code_limit
        if code_limit > sig_off:
            raise VerificationError(f"{exec_path}: codeLimit ({code_limit}) overlaps LC_CODE_SIGNATURE ({sig_off})")
        for page_idx in range(n_code_slots):
            start = page_idx * page_size
            end = min(start + page_size, code_limit)
            actual_page_hash = _cd_hash_bytes(macho[start:end], hash_type, hash_size)
            slot_off = hash_offset + page_idx * hash_size
            recorded_page_hash = cd[slot_off : slot_off + hash_size]
            if recorded_page_hash != actual_page_hash:
                raise VerificationError(
                    f"{exec_path}: CodeDirectory code page {page_idx} hash mismatch in slot 0x{slot_type:x}"
                )
        verified_cds += 1

    if require_developer_id and (is_adhoc or not has_cms_signature):
        raise VerificationError(f"{exec_path}: Developer ID signature required, but binary is ad-hoc signed")

    return {
        "arch": actual_arch,
        "cputype": f"0x{cputype:08x}",
        "codeDirectoriesVerified": verified_cds,
        "signatureKind": "developer-id" if (has_cms_signature and not is_adhoc) else "adhoc",
    }


def verify_app_bundle(
    app_dir: Path,
    expected_version: Optional[str] = None,
    expected_arch: Optional[str] = None,
    expected_bundle_id: str = "com.music.bitchord",
    require_developer_id: bool = False,
    run_host_codesign: bool = True,
) -> Dict[str, Any]:
    contents = app_dir / "Contents"
    if not contents.is_dir():
        raise VerificationError(f"{app_dir}: missing Contents/ directory")

    info_plist_path = contents / "Info.plist"
    if not info_plist_path.is_file():
        raise VerificationError(f"{app_dir}: missing Contents/Info.plist")
    info_plist_bytes = info_plist_path.read_bytes()
    try:
        info = plistlib.loads(info_plist_bytes)
    except Exception as exc:
        raise VerificationError(f"{info_plist_path}: malformed plist: {exc}") from exc

    bundle_id = str(info.get("CFBundleIdentifier", "")).strip()
    short_ver = str(info.get("CFBundleShortVersionString", "")).strip()
    bundle_ver = str(info.get("CFBundleVersion", "")).strip()
    exec_name = str(info.get("CFBundleExecutable", "")).strip()

    if bundle_id != expected_bundle_id:
        raise VerificationError(
            f"{info_plist_path}: CFBundleIdentifier='{bundle_id}', expected '{expected_bundle_id}'"
        )
    if not short_ver or not bundle_ver:
        raise VerificationError(
            f"{info_plist_path}: missing CFBundleShortVersionString ('{short_ver}') or CFBundleVersion ('{bundle_ver}')"
        )
    if not exec_name:
        raise VerificationError(f"{info_plist_path}: missing CFBundleExecutable")
    if expected_version and not version_matches(short_ver, expected_version):
        raise VerificationError(
            f"{info_plist_path}: CFBundleShortVersionString '{short_ver}' does not match expected '{expected_version}'"
        )

    exec_path = contents / "MacOS" / exec_name
    if not exec_path.is_file():
        raise VerificationError(f"{app_dir}: missing launcher executable Contents/MacOS/{exec_name}")

    # Verify Contents/app/Sonara.cfg and all classpath JARs
    cfg_path = contents / "app" / f"{exec_name}.cfg"
    if not cfg_path.is_file():
        raise VerificationError(f"{app_dir}: missing launcher configuration {cfg_path.relative_to(app_dir)}")

    cfg_lines = cfg_path.read_text(encoding="utf-8").splitlines()
    app_cfg_version: Optional[str] = None
    jvm_bitchord_version: Optional[str] = None
    classpath_entries: List[str] = []
    for raw_line in cfg_lines:
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith(";"):
            continue
        if line.startswith("app.version="):
            app_cfg_version = line.split("=", 1)[1].strip()
        elif line.startswith("app.classpath="):
            cp_val = line.split("=", 1)[1].strip()
            for token in cp_val.split(os.pathsep if os.pathsep in cp_val and ":" not in cp_val else ":"):
                if token.strip():
                    classpath_entries.append(token.strip())
        elif line.startswith("java-options=-Dbitchord.version="):
            jvm_bitchord_version = line.split("-Dbitchord.version=", 1)[1].strip()

    if expected_version:
        if jvm_bitchord_version and jvm_bitchord_version.lstrip("v") != expected_version.lstrip("v"):
            raise VerificationError(
                f"{cfg_path}: -Dbitchord.version='{jvm_bitchord_version}' != expected '{expected_version}'"
            )
        if app_cfg_version and not version_matches(app_cfg_version, expected_version):
            raise VerificationError(
                f"{cfg_path}: app.version='{app_cfg_version}' incompatible with expected '{expected_version}'"
            )

    if not classpath_entries:
        raise VerificationError(f"{cfg_path}: no app.classpath entries declared")

    verified_jars = 0
    for cp in classpath_entries:
        resolved_rel = (
            cp.replace("$APPDIR/", "app/")
            .replace("$APPDIR", "app")
            .replace("$ROOTDIR/", "")
            .replace("$ROOTDIR", "")
        )
        jar_path = contents / resolved_rel if not resolved_rel.startswith("Contents/") else app_dir / resolved_rel
        if not jar_path.is_file():
            # Fallback relative to Contents/app
            alt_jar = contents / "app" / cp
            if alt_jar.is_file():
                jar_path = alt_jar
            else:
                raise VerificationError(f"{cfg_path}: missing classpath JAR '{cp}' (looked at {jar_path})")
        if jar_path.stat().st_size == 0:
            raise VerificationError(f"{cfg_path}: empty 0-byte classpath JAR '{jar_path}'")
        verified_jars += 1

    # Verify _CodeSignature/CodeResources files2 SHA-256 digests
    code_res_path = contents / "_CodeSignature" / "CodeResources"
    if not code_res_path.is_file():
        raise VerificationError(f"{app_dir}: missing Contents/_CodeSignature/CodeResources")
    code_res_bytes = code_res_path.read_bytes()
    try:
        code_res = plistlib.loads(code_res_bytes)
    except Exception as exc:
        raise VerificationError(f"{code_res_path}: malformed plist: {exc}") from exc

    files2 = code_res.get("files2")
    if not isinstance(files2, dict) or not files2:
        raise VerificationError(f"{code_res_path}: missing or empty 'files2' dictionary")

    checked_files2 = 0
    for rel_key, spec in files2.items():
        disk_path = contents / rel_key
        if isinstance(spec, dict) and "symlink" in spec:
            if not disk_path.is_symlink():
                raise VerificationError(f"{code_res_path}: expected symlink at Contents/{rel_key}")
            actual_target = os.readlink(disk_path)
            if actual_target != spec["symlink"]:
                raise VerificationError(
                    f"{code_res_path}: symlink target mismatch at Contents/{rel_key}: '{actual_target}' != '{spec['symlink']}'"
                )
            checked_files2 += 1
            continue

        optional = isinstance(spec, dict) and bool(spec.get("optional", False))
        if not disk_path.exists() and not disk_path.is_symlink():
            if optional:
                continue
            raise VerificationError(f"{code_res_path}: missing sealed resource Contents/{rel_key}")

        file_bytes = disk_path.read_bytes()
        if isinstance(spec, dict):
            expected_sha256 = spec.get("hash2")
            expected_sha1 = spec.get("hash")
        elif isinstance(spec, bytes):
            expected_sha256 = spec if len(spec) == 32 else None
            expected_sha1 = spec if len(spec) == 20 else None
        else:
            raise VerificationError(f"{code_res_path}: invalid files2 entry for {rel_key}")

        if expected_sha256 is not None:
            actual_sha256 = hashlib.sha256(file_bytes).digest()
            if actual_sha256 != expected_sha256:
                raise VerificationError(
                    f"{code_res_path}: SHA-256 mismatch for Contents/{rel_key}: "
                    f"recorded={expected_sha256.hex()} != disk={actual_sha256.hex()}"
                )
        if expected_sha1 is not None:
            actual_sha1 = hashlib.sha1(file_bytes).digest()
            if actual_sha1 != expected_sha1:
                raise VerificationError(
                    f"{code_res_path}: SHA-1 mismatch for Contents/{rel_key}: "
                    f"recorded={expected_sha1.hex()} != disk={actual_sha1.hex()}"
                )
        checked_files2 += 1

    # Ensure every file in Contents/app and Contents/Resources is sealed in files2
    for must_seal_sub in ("app", "Resources"):
        sub_dir = contents / must_seal_sub
        if not sub_dir.is_dir():
            continue
        for root_str, _, filenames in os.walk(sub_dir):
            for fname in filenames:
                fpath = Path(root_str) / fname
                rel = fpath.relative_to(contents).as_posix()
                if rel not in files2:
                    raise VerificationError(f"{code_res_path}: unsealed file in bundle: Contents/{rel}")

    macho_summary = verify_macho_and_codesign(
        exec_path=exec_path,
        info_plist_bytes=info_plist_bytes,
        code_resources_bytes=code_res_bytes,
        expected_arch=expected_arch,
        require_developer_id=require_developer_id,
    )

    if sys.platform == "darwin" and run_host_codesign:
        cs_proc = subprocess.run(
            ["codesign", "--verify", "--deep", "--strict", str(app_dir)],
            capture_output=True,
            text=True,
        )
        if cs_proc.returncode != 0:
            raise VerificationError(
                f"{app_dir}: macOS codesign --verify --deep --strict failed:\n{cs_proc.stderr.strip()}"
            )
        if macho_summary["signatureKind"] == "developer-id":
            sp_proc = subprocess.run(
                ["spctl", "-a", "-vv", "--type", "execute", str(app_dir)],
                capture_output=True,
                text=True,
            )
            if sp_proc.returncode != 0 and require_developer_id:
                raise VerificationError(f"{app_dir}: spctl Gatekeeper check failed:\n{sp_proc.stderr.strip()}")

    return {
        "type": "app",
        "path": str(app_dir),
        "bundleId": bundle_id,
        "shortVersion": short_ver,
        "bundleVersion": bundle_ver,
        "jvmVersion": jvm_bitchord_version,
        "jarsVerified": verified_jars,
        "codeResourcesFilesVerified": checked_files2,
        **macho_summary,
    }


def verify_zip_artifact(
    zip_path: Path,
    expected_version: Optional[str] = None,
    expected_arch: Optional[str] = None,
    expected_bundle_id: str = "com.music.bitchord",
    require_developer_id: bool = False,
) -> Dict[str, Any]:
    arch = expected_arch or infer_arch_from_name(zip_path)
    with tempfile.TemporaryDirectory(prefix="bitchord-verify-zip-") as tmp:
        tmp_path = Path(tmp)
        extract_zip_preserving_symlinks(zip_path, tmp_path)
        apps = sorted(p for p in tmp_path.rglob("*.app") if p.is_dir() and ".app/" not in str(p.relative_to(tmp_path)))
        if not apps:
            raise VerificationError(f"{zip_path}: no .app bundle found in zip archive")
        app_res = verify_app_bundle(
            apps[0],
            expected_version=expected_version,
            expected_arch=arch,
            expected_bundle_id=expected_bundle_id,
            require_developer_id=require_developer_id,
        )
    app_res["type"] = "zip"
    app_res["path"] = str(zip_path)
    return app_res


# ---------------------------------------------------------------------------
# Minimal pure-Python HFS+ reader (extracts files & symlinks from Apple_HFS)
# ---------------------------------------------------------------------------


def _read_fork_bytes(volume: bytes, block_size: int, fork_header: bytes) -> bytes:
    logical_size, _clump, _total_blocks = struct.unpack(">QII", fork_header[:16])
    out = bytearray()
    for i in range(8):
        off = 16 + i * 8
        start_blk, blk_cnt = struct.unpack(">II", fork_header[off : off + 8])
        if blk_cnt == 0:
            break
        byte_start = start_blk * block_size
        byte_end = byte_start + blk_cnt * block_size
        out.extend(volume[byte_start:byte_end])
        if len(out) >= logical_size:
            break
    return bytes(out[:logical_size])


def extract_hfsplus_tree(volume: bytes, out_dir: Path) -> Dict[str, Any]:
    if len(volume) < 2048:
        raise VerificationError("HFS+ partition image too small (< 2048 bytes)")
    vh = volume[1024:1536]
    sig, version = struct.unpack(">2sH", vh[:4])
    if sig not in (b"H+", b"HX"):
        raise VerificationError(f"Unexpected HFS+ volume signature: {sig!r}")
    block_size = struct.unpack(">I", vh[40:44])[0]
    if block_size == 0 or (block_size & (block_size - 1)) != 0:
        raise VerificationError(f"Invalid HFS+ blockSize: {block_size}")

    catalog_bytes = _read_fork_bytes(volume, block_size, vh[272:352])
    if len(catalog_bytes) < 512:
        raise VerificationError("HFS+ Catalog file is truncated")

    node_size = struct.unpack(">H", catalog_bytes[14 + 18 : 14 + 20])[0]
    first_leaf = struct.unpack(">I", catalog_bytes[14 + 10 : 14 + 14])[0]
    if node_size < 512:
        raise VerificationError(f"Invalid HFS+ Catalog nodeSize: {node_size}")

    folders: Dict[int, Tuple[int, str]] = {}
    files: List[Tuple[int, str, int, bytes, bytes]] = []

    node_idx = first_leaf
    visited = set()
    while node_idx != 0:
        if node_idx in visited:
            raise VerificationError("Cycle detected in HFS+ Catalog leaf chain")
        visited.add(node_idx)
        n_start = node_idx * node_size
        node = catalog_bytes[n_start : n_start + node_size]
        if len(node) < node_size:
            raise VerificationError(f"HFS+ Catalog leaf node {node_idx} out of bounds")
        f_link, _b_link, kind, _height, num_recs, _ = struct.unpack(">IIbBHH", node[:14])
        if kind != -1:
            raise VerificationError(f"Expected HFS+ leaf node (-1) at {node_idx}, got {kind}")
        offsets = [
            struct.unpack(">H", node[node_size - 2 * (i + 1) : node_size - 2 * i])[0]
            for i in range(num_recs + 1)
        ]
        for i in range(num_recs):
            rec = node[offsets[i] : offsets[i + 1]]
            key_len = struct.unpack(">H", rec[:2])[0]
            parent_id, name_chars = struct.unpack(">IH", rec[2:8])
            name = rec[8 : 8 + name_chars * 2].decode("utf-16-be", errors="replace")
            val_off = 2 + key_len
            if val_off & 1:
                val_off += 1
            rec_type = struct.unpack(">h", rec[val_off : val_off + 2])[0]
            if rec_type == 1:  # kHFSPlusFolderRecord
                folder_id = struct.unpack(">I", rec[val_off + 8 : val_off + 12])[0]
                folders[folder_id] = (parent_id, name)
            elif rec_type == 2:  # kHFSPlusFileRecord
                file_mode = struct.unpack(">H", rec[val_off + 42 : val_off + 44])[0]
                user_info = rec[val_off + 48 : val_off + 64]
                data_fork = rec[val_off + 88 : val_off + 168]
                files.append((parent_id, name, file_mode, user_info, data_fork))
        node_idx = f_link

    def resolve_folder_rel(fid: int) -> Optional[Path]:
        if fid == 2:
            return Path(".")
        parts: List[str] = []
        cur = fid
        guard = 0
        while cur != 2:
            if cur not in folders or guard > 128:
                return None
            pid, nm = folders[cur]
            if pid == 1:
                return Path(".")
            parts.append(nm)
            cur = pid
            guard += 1
        return Path(*reversed(parts)) if parts else Path(".")

    for fid in folders:
        rel = resolve_folder_rel(fid)
        if rel is not None and rel != Path(".") and "\x00" not in str(rel):
            (out_dir / rel).mkdir(parents=True, exist_ok=True)

    extracted_files = 0
    extracted_symlinks = 0
    for parent_id, name, file_mode, user_info, data_fork in files:
        parent_rel = resolve_folder_rel(parent_id)
        if parent_rel is None or "\x00" in name or "\x00" in str(parent_rel):
            continue
        dest = out_dir / parent_rel / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        raw_data = _read_fork_bytes(volume, block_size, data_fork)
        is_symlink = (file_mode & 0o170000) == stat.S_IFLNK or user_info[:4] == b"slnk"
        if is_symlink:
            target_str = raw_data.decode("utf-8")
            if dest.exists() or dest.is_symlink():
                dest.unlink()
            os.symlink(target_str, dest)
            extracted_symlinks += 1
        else:
            dest.write_bytes(raw_data)
            perms = file_mode & 0o7777
            if perms:
                os.chmod(dest, perms)
            extracted_files += 1

    return {
        "signature": sig.decode("ascii"),
        "version": version,
        "blockSize": block_size,
        "extractedFiles": extracted_files,
        "extractedSymlinks": extracted_symlinks,
    }


def verify_dmg_artifact(
    dmg_path: Path,
    expected_version: Optional[str] = None,
    expected_arch: Optional[str] = None,
    expected_bundle_id: str = "com.music.bitchord",
    require_developer_id: bool = False,
) -> Dict[str, Any]:
    arch = expected_arch or infer_arch_from_name(dmg_path)
    dmg_bytes = dmg_path.read_bytes()
    if len(dmg_bytes) < 512:
        raise VerificationError(f"{dmg_path}: file too small (< 512 bytes) to contain UDIF koly trailer")

    koly = dmg_bytes[-512:]
    sig, ver, hdr_size, _flags = struct.unpack(">4sIII", koly[:16])
    if sig != KOLY_MAGIC:
        raise VerificationError(f"{dmg_path}: missing UDIF 'koly' trailer signature (got {sig!r})")
    if ver != 4 or hdr_size != 512:
        raise VerificationError(f"{dmg_path}: unexpected koly version={ver} headerSize={hdr_size}")

    df_off, df_len = struct.unpack(">QQ", koly[24:40])
    df_chk_type, df_chk_bits, df_chk_crc = struct.unpack(">III", koly[80:92])
    xml_off, xml_len = struct.unpack(">QQ", koly[216:232])
    master_chk_type, master_chk_bits, master_chk_crc = struct.unpack(">III", koly[352:364])
    total_sectors = struct.unpack(">Q", koly[492:500])[0]

    if df_off + df_len > len(dmg_bytes) - 512:
        raise VerificationError(f"{dmg_path}: DataFork ({df_off}+{df_len}) overlaps trailer")
    if xml_off + xml_len > len(dmg_bytes) - 512 or xml_len == 0:
        raise VerificationError(f"{dmg_path}: XML plist ({xml_off}+{xml_len}) out of bounds")

    # Verify Data Fork CRC-32 (koly offset 80..92)
    calc_df_crc = zlib.crc32(dmg_bytes[df_off : df_off + df_len]) & 0xFFFFFFFF
    if df_chk_type == UDIF_CHECKSUM_CRC32:
        if df_chk_bits != 32:
            raise VerificationError(f"{dmg_path}: DataFork checksum bit size is {df_chk_bits}, expected 32")
        if calc_df_crc != df_chk_crc:
            raise VerificationError(
                f"{dmg_path}: Data Fork CRC-32 mismatch (koly 80..92): recorded=0x{df_chk_crc:08x} != actual=0x{calc_df_crc:08x}"
            )
    else:
        raise VerificationError(
            f"{dmg_path}: Data Fork CRC-32 (koly 80..92) missing or unexpected type={df_chk_type}"
        )

    raw_xml = dmg_bytes[xml_off : xml_off + xml_len]
    if raw_xml[:2] in (b"\x78\x01", b"\x78\x5e", b"\x78\x9c", b"\x78\xda"):
        raw_xml = zlib.decompress(raw_xml)
    try:
        plist = plistlib.loads(raw_xml)
    except Exception as exc:
        raise VerificationError(f"{dmg_path}: failed to parse UDIF XML plist: {exc}") from exc

    blkx_list = plist.get("resource-fork", {}).get("blkx", [])
    if not isinstance(blkx_list, list) or not blkx_list:
        raise VerificationError(f"{dmg_path}: UDIF plist contains no resource-fork.blkx entries")

    mish_crcs_for_master: List[int] = []
    hfs_partition_image: Optional[bytes] = None
    max_zlib_run_seen = 0
    total_runs_checked = 0

    for idx, entry in enumerate(blkx_list):
        name = str(entry.get("Name") or entry.get("CFName") or f"blkx[{idx}]")
        mish = entry.get("Data")
        if not isinstance(mish, bytes) or len(mish) < 204:
            raise VerificationError(f"{dmg_path}: {name} mish descriptor truncated")

        m_sig, m_ver, m_first_sec, m_sec_cnt, m_data_off, m_bufs_needed, _m_desc = struct.unpack(
            ">4sIQQQII", mish[:40]
        )
        if m_sig != MISH_MAGIC or m_ver != 1:
            raise VerificationError(f"{dmg_path}: {name} invalid mish header ({m_sig!r}, v={m_ver})")

        m_chk_type, m_chk_bits, m_chk_crc = struct.unpack(">III", mish[64:76])
        n_runs = struct.unpack(">I", mish[200:204])[0]
        if 204 + n_runs * 40 > len(mish):
            raise VerificationError(f"{dmg_path}: {name} mish run table ({n_runs} runs) exceeds descriptor size")

        is_hfs = ("Apple_HFS" in name) or (len(blkx_list) == 1)
        part_buf = bytearray(m_sec_cnt * SECTOR_SIZE) if is_hfs else None
        run_crc = 0
        saw_terminator = False

        for r in range(n_runs):
            roff = 204 + r * 40
            rtype, _rcomm, rsec, rcnt, rcoff, rclen = struct.unpack(">IIQQQQ", mish[roff : roff + 40])
            total_runs_checked += 1

            if rtype == BLKX_RUN_TERM:
                saw_terminator = True
                break
            if rtype == BLKX_RUN_COMMENT:
                continue

            if rsec + rcnt > m_sec_cnt:
                raise VerificationError(
                    f"{dmg_path}: {name} run {r} sectors ({rsec}+{rcnt}) exceed partition sectorCount ({m_sec_cnt})"
                )

            if rtype == BLKX_RUN_ZLIB:
                if rcnt > MAX_ZLIB_RUN_SECTORS:
                    raise VerificationError(
                        f"{dmg_path}: {name} run {r} BLKX_RUN_ZLIB sectorCount={rcnt} exceeds 2048-sector UDIF limit!"
                    )
                if m_bufs_needed and rcnt > m_bufs_needed:
                    raise VerificationError(
                        f"{dmg_path}: {name} run {r} BLKX_RUN_ZLIB sectorCount={rcnt} exceeds DecompressionBufferRequested={m_bufs_needed}!"
                    )
                if rcnt > max_zlib_run_seen:
                    max_zlib_run_seen = rcnt

            if rtype in (BLKX_RUN_ZERO, BLKX_RUN_IGNORE):
                continue

            abs_off = m_data_off + rcoff
            if abs_off + rclen > df_off + df_len:
                raise VerificationError(
                    f"{dmg_path}: {name} run {r} compressed range ({abs_off}+{rclen}) outside DataFork"
                )
            comp_slice = dmg_bytes[abs_off : abs_off + rclen]

            if rtype == BLKX_RUN_RAW:
                uncomp = comp_slice
            elif rtype == BLKX_RUN_ZLIB:
                try:
                    uncomp = zlib.decompress(comp_slice)
                except Exception as exc:
                    raise VerificationError(f"{dmg_path}: {name} run {r} zlib decompression failed: {exc}") from exc
            elif rtype == BLKX_RUN_BZ2:
                uncomp = bz2.decompress(comp_slice)
            else:
                raise VerificationError(f"{dmg_path}: {name} run {r} unsupported run type 0x{rtype:08x}")

            expected_bytes = rcnt * SECTOR_SIZE
            if len(uncomp) != expected_bytes:
                raise VerificationError(
                    f"{dmg_path}: {name} run {r} decompressed {len(uncomp)} bytes, expected {expected_bytes}"
                )

            run_crc = zlib.crc32(uncomp, run_crc) & 0xFFFFFFFF
            if part_buf is not None:
                p_start = rsec * SECTOR_SIZE
                part_buf[p_start : p_start + expected_bytes] = uncomp

        if not saw_terminator:
            raise VerificationError(f"{dmg_path}: {name} mish run table missing 0xffffffff terminator")

        if m_chk_type == UDIF_CHECKSUM_CRC32:
            if m_chk_bits != 32:
                raise VerificationError(f"{dmg_path}: {name} mish checksum bit size={m_chk_bits}, expected 32")
            if run_crc != m_chk_crc:
                raise VerificationError(
                    f"{dmg_path}: {name} mish CRC-32 mismatch: recorded=0x{m_chk_crc:08x} != actual=0x{run_crc:08x}"
                )
            mish_crcs_for_master.append(m_chk_crc)

        if part_buf is not None and len(part_buf) >= 2048 and part_buf[1024:1026] in (b"H+", b"HX"):
            hfs_partition_image = bytes(part_buf)

    if master_chk_type != UDIF_CHECKSUM_CRC32 or master_chk_bits != 32:
        raise VerificationError(
            f"{dmg_path}: Master checksum (koly 352..364) invalid type={master_chk_type} bits={master_chk_bits}"
        )
    master_buf = b"".join(struct.pack(">I", c) for c in mish_crcs_for_master)
    calc_master_crc = zlib.crc32(master_buf) & 0xFFFFFFFF
    if calc_master_crc != master_chk_crc:
        raise VerificationError(
            f"{dmg_path}: Master CRC-32 mismatch (koly 352..364): recorded=0x{master_chk_crc:08x} != actual=0x{calc_master_crc:08x}"
        )

    if hfs_partition_image is None:
        raise VerificationError(f"{dmg_path}: no Apple_HFS / HFS+ partition found in blkx descriptors")

    fsck_tool = find_host_tool(["fsck.hfsplus", "fsck_hfs"])
    fsck_ran = False
    with tempfile.TemporaryDirectory(prefix="bitchord-verify-dmg-") as tmp:
        tmp_dir = Path(tmp)
        hfs_img_path = tmp_dir / "partition.hfs"
        hfs_img_path.write_bytes(hfs_partition_image)

        if fsck_tool:
            proc = subprocess.run([fsck_tool, "-fn", str(hfs_img_path)], capture_output=True, text=True)
            if proc.returncode != 0:
                raise VerificationError(
                    f"{dmg_path}: {fsck_tool} -fn failed (rc={proc.returncode}):\n{proc.stdout}\n{proc.stderr}"
                )
            fsck_ran = True

        extracted_root = tmp_dir / "mounted"
        extracted_root.mkdir()
        hfs_info = extract_hfsplus_tree(hfs_partition_image, extracted_root)

        apps_link = extracted_root / "Applications"
        valid_apps_link = (
            (apps_link.is_symlink() and os.readlink(apps_link) == "/Applications")
            or (apps_link.is_file() and apps_link.read_bytes()[:4] == b"book")
        )
        if not valid_apps_link:
            raise VerificationError(
                f"{dmg_path}: DMG root is missing '/Applications' symlink or Finder alias bookmark"
            )

        app_candidates = sorted(p for p in extracted_root.glob("*.app") if p.is_dir())
        if not app_candidates:
            raise VerificationError(f"{dmg_path}: no .app bundle found inside HFS+ volume")

        app_summary = verify_app_bundle(
            app_candidates[0],
            expected_version=expected_version,
            expected_arch=arch,
            expected_bundle_id=expected_bundle_id,
            require_developer_id=require_developer_id,
            run_host_codesign=(sys.platform == "darwin"),
        )

    if sys.platform == "darwin":
        hv = subprocess.run(["hdiutil", "verify", str(dmg_path)], capture_output=True, text=True)
        if hv.returncode != 0:
            raise VerificationError(f"{dmg_path}: hdiutil verify failed:\n{hv.stdout}\n{hv.stderr}")

    return {
        "type": "dmg",
        "path": str(dmg_path),
        "totalSectors": total_sectors,
        "blkxCount": len(blkx_list),
        "runsChecked": total_runs_checked,
        "maxZlibRunSectors": max_zlib_run_seen,
        "dataForkCrc32": f"0x{calc_df_crc:08x}",
        "masterCrc32": f"0x{calc_master_crc:08x}",
        "fsckHfsplusVerified": fsck_ran,
        "hfs": hfs_info,
        "app": app_summary,
    }


# ---------------------------------------------------------------------------
# BOM & ODC cpio parsers for .pkg verification
# ---------------------------------------------------------------------------


def parse_bom(bom_bytes: bytes) -> Dict[str, Dict[str, Any]]:
    if len(bom_bytes) < 32 or bom_bytes[:8] != BOM_MAGIC:
        raise VerificationError("Bom does not start with 'BOMStore' magic")
    ver, _num_blocks, idx_off, idx_len, vars_off, vars_len = struct.unpack(">IIIIII", bom_bytes[8:32])
    if ver != 1:
        raise VerificationError(f"Unsupported BOMStore version: {ver}")
    if idx_off + idx_len > len(bom_bytes) or vars_off + vars_len > len(bom_bytes):
        raise VerificationError("BOMStore index or vars table out of bounds")

    ptr_count = struct.unpack(">I", bom_bytes[idx_off : idx_off + 4])[0]
    if idx_off + 4 + ptr_count * 8 > len(bom_bytes):
        raise VerificationError("BOMStore block pointer table truncated")
    ptrs: List[Tuple[int, int]] = []
    for i in range(ptr_count):
        p_off = idx_off + 4 + i * 8
        ptrs.append(struct.unpack(">II", bom_bytes[p_off : p_off + 8]))

    def get_block(block_id: int) -> bytes:
        if block_id <= 0 or block_id >= len(ptrs):
            raise VerificationError(f"BOMStore invalid block index {block_id}")
        addr, length = ptrs[block_id]
        if addr + length > len(bom_bytes):
            raise VerificationError(f"BOMStore block {block_id} ({addr}+{length}) out of bounds")
        return bom_bytes[addr : addr + length]

    var_count = struct.unpack(">I", bom_bytes[vars_off : vars_off + 4])[0]
    vpos = vars_off + 4
    vars_map: Dict[str, int] = {}
    for _ in range(var_count):
        b_idx, n_len = struct.unpack(">IB", bom_bytes[vpos : vpos + 5])
        vpos += 5
        v_name = bom_bytes[vpos : vpos + n_len].decode("utf-8", errors="replace")
        vpos += n_len
        vars_map[v_name] = b_idx

    if "Paths" not in vars_map:
        raise VerificationError("BOMStore missing 'Paths' variable")

    tree_blk = get_block(vars_map["Paths"])
    if len(tree_blk) < 21 or tree_blk[:4] != b"tree":
        raise VerificationError("BOMStore 'Paths' block is not a valid B-Tree header")
    _t_ver, root_child, _blk_sz, _path_cnt, _unk = struct.unpack(">IIIIB", tree_blk[4:21])

    # Walk down to leftmost leaf, then follow forward links
    cur_id = root_child
    guard = 0
    while cur_id != 0:
        guard += 1
        if guard > 10000:
            raise VerificationError("BOMStore B-Tree descent exceeded depth limit")
        node = get_block(cur_id)
        is_leaf, count, _fwd, _bwd = struct.unpack(">HHII", node[:12])
        if is_leaf != 0:
            break
        if count == 0:
            cur_id = 0
            break
        cur_id = struct.unpack(">I", node[12:16])[0]

    raw_nodes: Dict[int, Tuple[int, str, Dict[str, Any]]] = {}
    visited_leaves = set()
    while cur_id != 0:
        if cur_id in visited_leaves:
            raise VerificationError("Cycle in BOMStore Paths leaf chain")
        visited_leaves.add(cur_id)
        node = get_block(cur_id)
        is_leaf, count, fwd, _bwd = struct.unpack(">HHII", node[:12])
        if is_leaf == 0:
            raise VerificationError("Non-leaf encountered in BOMStore leaf chain")
        for i in range(count):
            idx0, idx1 = struct.unpack(">II", node[12 + i * 8 : 20 + i * 8])
            pinfo1 = get_block(idx0)
            path_id, rec_idx = struct.unpack(">II", pinfo1[:8])
            key_blk = get_block(idx1)
            parent_id = struct.unpack(">I", key_blk[:4])[0]
            nul = key_blk.find(b"\x00", 4)
            name = key_blk[4 : (nul if nul != -1 else len(key_blk))].decode("utf-8", errors="replace")

            rec = get_block(rec_idx)
            if len(rec) < 31:
                raise VerificationError(f"BOMStore PathRecord too short ({len(rec)} bytes) for '{name}'")
            p_type, _u0, _arch, mode, uid, gid, mtime, size, _u1, cksum, link_len = struct.unpack(
                ">BBHHIIIIBII", rec[:31]
            )
            link_target = ""
            if p_type == 3 and link_len > 0:
                raw_link = rec[31 : 31 + link_len]
                link_target = raw_link.rstrip(b"\x00").decode("utf-8", errors="replace")
            raw_nodes[path_id] = (
                parent_id,
                name,
                {
                    "type": p_type,
                    "mode": mode,
                    "uid": uid,
                    "gid": gid,
                    "mtime": mtime,
                    "size": size,
                    "cksum": cksum,
                    "linkTarget": link_target,
                },
            )
        cur_id = fwd

    entries: Dict[str, Dict[str, Any]] = {}
    for pid, (parent_id, name, meta) in raw_nodes.items():
        parts = [name]
        cur = parent_id
        depth = 0
        while cur != 0 and cur in raw_nodes:
            p_parent, p_name, _ = raw_nodes[cur]
            parts.append(p_name)
            cur = p_parent
            depth += 1
            if depth > 256:
                raise VerificationError("Cycle in BOMStore parent hierarchy")
        full_path = "/".join(reversed(parts))
        entries[full_path] = meta

    return entries


def extract_cpio_odc(cpio_bytes: bytes, dest_dir: Path) -> Dict[str, Tuple[int, bytes, Optional[str]]]:
    """Parses portable ASCII odc (070707) or SVR4 newc (070701) cpio into dest_dir."""
    pos = 0
    dest_root = dest_dir.resolve()
    entries: Dict[str, Tuple[int, bytes, Optional[str]]] = {}

    while pos < len(cpio_bytes):
        magic = cpio_bytes[pos : pos + 6]
        if magic == b"070707":
            if pos + 76 > len(cpio_bytes):
                raise VerificationError("Truncated odc cpio header")
            hdr = cpio_bytes[pos : pos + 76].decode("ascii")
            mode = int(hdr[18:24], 8)
            namesize = int(hdr[59:65], 8)
            filesize = int(hdr[65:76], 8)
            pos += 76
            raw_name = cpio_bytes[pos : pos + namesize]
            pos += namesize
            data = cpio_bytes[pos : pos + filesize]
            pos += filesize
        elif magic in (b"070701", b"070702"):
            if pos + 110 > len(cpio_bytes):
                raise VerificationError("Truncated newc cpio header")
            hdr = cpio_bytes[pos : pos + 110].decode("ascii")
            mode = int(hdr[14:22], 16)
            filesize = int(hdr[54:62], 16)
            namesize = int(hdr[94:102], 16)
            pos += 110
            raw_name = cpio_bytes[pos : pos + namesize]
            pos += namesize
            if pos & 3:
                pos += 4 - (pos & 3)
            data = cpio_bytes[pos : pos + filesize]
            pos += filesize
            if pos & 3:
                pos += 4 - (pos & 3)
        elif set(cpio_bytes[pos:]) <= {0}:
            break
        else:
            raise VerificationError(f"Unsupported cpio header magic {magic!r} at offset {pos}")

        name = raw_name.rstrip(b"\x00").decode("utf-8")
        if name == "TRAILER!!!":
            break

        norm_key = name if name.startswith(".") else f"./{name.lstrip('/')}"
        ftype = mode & 0o170000
        rel_clean = norm_key.lstrip("./")
        target = dest_root / rel_clean if rel_clean else dest_root
        parent_res = target.parent.resolve() if rel_clean else dest_root
        if parent_res != dest_root and dest_root not in parent_res.parents:
            raise VerificationError(f"Unsafe path in Payload cpio: {name}")

        if ftype == stat.S_IFDIR or norm_key == ".":
            target.mkdir(parents=True, exist_ok=True)
            entries[norm_key] = (mode, b"", None)
        elif ftype == stat.S_IFLNK:
            link_str = data.decode("utf-8")
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists() or target.is_symlink():
                target.unlink()
            os.symlink(link_str, target)
            entries[norm_key] = (mode, data, link_str)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            perms = mode & 0o7777
            if perms:
                os.chmod(target, perms)
            entries[norm_key] = (mode, data, None)

    return entries


def _hash_by_style(data: bytes, style: str) -> str:
    st = style.lower()
    if st == "sha1":
        return hashlib.sha1(data).hexdigest()
    if st == "sha256":
        return hashlib.sha256(data).hexdigest()
    if st == "sha512":
        return hashlib.sha512(data).hexdigest()
    if st == "md5":
        return hashlib.md5(data).hexdigest()
    raise VerificationError(f"Unsupported XAR checksum style: {style}")


def verify_pkg_artifact(
    pkg_path: Path,
    expected_version: Optional[str] = None,
    expected_arch: Optional[str] = None,
    expected_bundle_id: str = "com.music.bitchord",
    require_developer_id: bool = False,
) -> Dict[str, Any]:
    arch = expected_arch or infer_arch_from_name(pkg_path)
    pkg_bytes = pkg_path.read_bytes()
    if len(pkg_bytes) < 28:
        raise VerificationError(f"{pkg_path}: file too small (< 28 bytes) to be a XAR .pkg")

    magic, hdr_size, xar_ver, toc_comp_len, toc_uncomp_len, cksum_alg = struct.unpack(
        ">4sHHQQI", pkg_bytes[:28]
    )
    if magic != XAR_MAGIC:
        raise VerificationError(f"{pkg_path}: missing 'xar!' header magic (got {magic!r})")
    if hdr_size < 28 or xar_ver != 1:
        raise VerificationError(f"{pkg_path}: invalid XAR header size={hdr_size} version={xar_ver}")

    toc_start = hdr_size
    toc_end = toc_start + toc_comp_len
    if toc_end > len(pkg_bytes):
        raise VerificationError(f"{pkg_path}: XAR compressed TOC exceeds file length")

    toc_comp = pkg_bytes[toc_start:toc_end]
    if toc_comp[:2] != b"\x78\xda":
        raise VerificationError(
            f"{pkg_path}: XAR TOC stream must start with raw zlib 0x78 0xda header (got {toc_comp[:2].hex(' ')})"
        )
    try:
        toc_xml_bytes = zlib.decompress(toc_comp)
    except Exception as exc:
        raise VerificationError(f"{pkg_path}: failed to decompress XAR TOC: {exc}") from exc

    if len(toc_xml_bytes) != toc_uncomp_len:
        raise VerificationError(
            f"{pkg_path}: XAR TOC uncompressed length {len(toc_xml_bytes)} != header {toc_uncomp_len}"
        )

    heap_offset = toc_end
    try:
        root = ET.fromstring(toc_xml_bytes.decode("utf-8"))
    except Exception as exc:
        raise VerificationError(f"{pkg_path}: malformed XAR TOC XML: {exc}") from exc

    toc_el = root.find("toc")
    if toc_el is None:
        raise VerificationError(f"{pkg_path}: XAR XML missing <toc> element")

    toc_cksum_el = toc_el.find("checksum")
    if toc_cksum_el is not None and cksum_alg != 0:
        c_style = toc_cksum_el.attrib.get("style", "sha1")
        c_off = int(toc_cksum_el.findtext("offset", "0"))
        c_size = int(toc_cksum_el.findtext("size", "20"))
        recorded_toc_digest = pkg_bytes[heap_offset + c_off : heap_offset + c_off + c_size].hex()
        actual_toc_digest = _hash_by_style(toc_comp, c_style)
        if recorded_toc_digest != actual_toc_digest:
            raise VerificationError(
                f"{pkg_path}: XAR TOC heap checksum mismatch: {recorded_toc_digest} != {actual_toc_digest}"
            )

    xar_files: Dict[str, bytes] = {}
    files_verified = 0

    def walk_xar_files(parent: ET.Element, prefix: str) -> None:
        nonlocal files_verified
        for f_el in parent.findall("file"):
            fname = (f_el.findtext("name") or "").strip()
            ftype = (f_el.findtext("type") or "file").strip()
            full_name = f"{prefix}/{fname}" if prefix else fname
            data_el = f_el.find("data")
            if data_el is not None:
                d_off = int(data_el.findtext("offset", "0"))
                d_len = int(data_el.findtext("length", "0"))
                d_size = int(data_el.findtext("size", "0"))
                enc_el = data_el.find("encoding")
                enc_style = enc_el.attrib.get("style", "application/octet-stream") if enc_el is not None else "application/octet-stream"

                abs_start = heap_offset + d_off
                abs_end = abs_start + d_len
                if abs_end > len(pkg_bytes):
                    raise VerificationError(f"{pkg_path}: XAR entry '{full_name}' data out of bounds")
                archived_bytes = pkg_bytes[abs_start:abs_end]

                arch_chk_el = data_el.find("archived-checksum")
                if arch_chk_el is None or not (arch_chk_el.text or "").strip():
                    raise VerificationError(f"{pkg_path}: XAR entry '{full_name}' missing <archived-checksum>")
                arch_style = arch_chk_el.attrib.get("style", "sha1")
                expected_arch_hash = arch_chk_el.text.strip().lower()
                actual_arch_hash = _hash_by_style(archived_bytes, arch_style)
                if actual_arch_hash != expected_arch_hash:
                    raise VerificationError(
                        f"{pkg_path}: XAR '{full_name}' archived-checksum ({arch_style}) mismatch: "
                        f"{expected_arch_hash} != {actual_arch_hash}"
                    )

                if enc_style in ("application/x-gzip", "application/zlib"):
                    if archived_bytes[:2] != b"\x78\xda":
                        raise VerificationError(
                            f"{pkg_path}: XAR '{full_name}' zlib stream must start with 0x78 0xda (got {archived_bytes[:2].hex(' ')})"
                        )
                    extracted_bytes = zlib.decompress(archived_bytes)
                elif enc_style == "application/octet-stream":
                    extracted_bytes = archived_bytes
                elif enc_style == "application/x-bzip2":
                    extracted_bytes = bz2.decompress(archived_bytes)
                else:
                    raise VerificationError(f"{pkg_path}: unsupported XAR encoding '{enc_style}' on '{full_name}'")

                if len(extracted_bytes) != d_size:
                    raise VerificationError(
                        f"{pkg_path}: XAR '{full_name}' extracted size {len(extracted_bytes)} != {d_size}"
                    )

                ext_chk_el = data_el.find("extracted-checksum")
                if ext_chk_el is None or not (ext_chk_el.text or "").strip():
                    raise VerificationError(f"{pkg_path}: XAR entry '{full_name}' missing <extracted-checksum>")
                ext_style = ext_chk_el.attrib.get("style", "sha1")
                expected_ext_hash = ext_chk_el.text.strip().lower()
                actual_ext_hash = _hash_by_style(extracted_bytes, ext_style)
                if actual_ext_hash != expected_ext_hash:
                    raise VerificationError(
                        f"{pkg_path}: XAR '{full_name}' extracted-checksum ({ext_style}) mismatch: "
                        f"{expected_ext_hash} != {actual_ext_hash}"
                    )

                xar_files[full_name] = extracted_bytes
                files_verified += 1

            if ftype == "directory" or f_el.find("file") is not None:
                walk_xar_files(f_el, full_name)

    walk_xar_files(toc_el, "")

    bom_key = next((k for k in xar_files if k == "Bom" or k.endswith("/Bom")), None)
    pkginfo_key = next((k for k in xar_files if k == "PackageInfo" or k.endswith("/PackageInfo")), None)
    payload_key = next((k for k in xar_files if k == "Payload" or k.endswith("/Payload")), None)

    if not bom_key or not pkginfo_key or not payload_key:
        raise VerificationError(
            f"{pkg_path}: missing required flat-package members (Bom={bom_key}, PackageInfo={pkginfo_key}, Payload={payload_key})"
        )

    # 1. Verify PackageInfo XML
    pkginfo_el = ET.fromstring(xar_files[pkginfo_key].decode("utf-8"))
    pkg_id = pkginfo_el.attrib.get("identifier", "")
    pkg_ver = pkginfo_el.attrib.get("version", "")
    install_loc = pkginfo_el.attrib.get("install-location", "/")
    if not pkg_id or not pkg_ver:
        raise VerificationError(f"{pkg_path}: PackageInfo missing identifier or version")
    if expected_version and not version_matches(pkg_ver, expected_version):
        raise VerificationError(
            f"{pkg_path}: PackageInfo version '{pkg_ver}' does not match expected '{expected_version}'"
        )

    # 2. Verify Bom (BOMStore)
    bom_entries = parse_bom(xar_files[bom_key])
    required_bom_suffixes = (
        "Sonara.app/Contents/Info.plist",
        "Sonara.app/Contents/MacOS/Sonara",
        "Sonara.app/Contents/app/Sonara.cfg",
    )
    for req_sfx in required_bom_suffixes:
        if not any(p.endswith(req_sfx) for p in bom_entries):
            raise VerificationError(f"{pkg_path}: Bom is missing entry ending with '{req_sfx}'")

    # 3. Verify Payload (stripped gzip header + bsdtar -tzf + cpio + Bom cksum parity + .app verification)
    payload_bytes = xar_files[payload_key]
    if len(payload_bytes) < 10 or payload_bytes[:8] != STRIPPED_GZIP_PREFIX:
        raise VerificationError(
            f"{pkg_path}: Payload must start with stripped gzip header '1f 8b 08 00 00 00 00 00' "
            f"(got {payload_bytes[:8].hex(' ')})"
        )

    bsdtar_tool = find_host_tool(["bsdtar"])
    bsdtar_verified = False
    with tempfile.TemporaryDirectory(prefix="bitchord-verify-pkg-") as tmp:
        tmp_dir = Path(tmp)
        payload_file = tmp_dir / "Payload"
        payload_file.write_bytes(payload_bytes)

        if bsdtar_tool:
            tar_proc = subprocess.run([bsdtar_tool, "-tzf", str(payload_file)], capture_output=True, text=True)
            if tar_proc.returncode != 0:
                raise VerificationError(
                    f"{pkg_path}: bsdtar -tzf Payload failed (rc={tar_proc.returncode}):\n{tar_proc.stderr.strip()}"
                )
            listed = tar_proc.stdout.splitlines()
            if not any("Sonara.app/Contents/MacOS/Sonara" in item for item in listed):
                raise VerificationError(f"{pkg_path}: bsdtar -tzf Payload did not list Sonara executable")
            bsdtar_verified = True

        cpio_raw = gzip.decompress(payload_bytes)
        payload_root = tmp_dir / "payload_root"
        payload_root.mkdir()
        cpio_entries = extract_cpio_odc(cpio_raw, payload_root)

        # Cross-verify Bom regular file sizes and POSIX cksums against Payload entries
        cksums_verified = 0
        for bom_path, bom_meta in bom_entries.items():
            if bom_meta["type"] != 1 or os.path.basename(bom_path).startswith("._"):
                continue
            if bom_path not in cpio_entries:
                raise VerificationError(f"{pkg_path}: Bom file '{bom_path}' missing from Payload cpio")
            _cmode, cdata, _clink = cpio_entries[bom_path]
            if len(cdata) != bom_meta["size"]:
                raise VerificationError(
                    f"{pkg_path}: Bom size mismatch for '{bom_path}': Bom={bom_meta['size']} != Payload={len(cdata)}"
                )
            calc_ck = posix_cksum(cdata)
            if calc_ck != bom_meta["cksum"]:
                raise VerificationError(
                    f"{pkg_path}: Bom POSIX cksum mismatch for '{bom_path}': "
                    f"Bom=0x{bom_meta['cksum']:08x} != Payload=0x{calc_ck:08x}"
                )
            cksums_verified += 1

        apps = sorted(
            p for p in payload_root.rglob("*.app") if p.is_dir() and ".app/" not in str(p.relative_to(payload_root))
        )
        if not apps:
            raise VerificationError(f"{pkg_path}: no .app bundle extracted from Payload")
        app_summary = verify_app_bundle(
            apps[0],
            expected_version=expected_version,
            expected_arch=arch,
            expected_bundle_id=expected_bundle_id,
            require_developer_id=require_developer_id,
            run_host_codesign=(sys.platform == "darwin"),
        )

    has_xar_sig = toc_el.find("signature") is not None or toc_el.find("x-signature") is not None
    if require_developer_id and not has_xar_sig:
        raise VerificationError(f"{pkg_path}: installer signature required, but XAR TOC has no <signature>")

    if sys.platform == "darwin" and has_xar_sig:
        pk_proc = subprocess.run(["pkgutil", "--check-signature", str(pkg_path)], capture_output=True, text=True)
        if pk_proc.returncode != 0:
            raise VerificationError(
                f"{pkg_path}: pkgutil --check-signature failed:\n{pk_proc.stdout}\n{pk_proc.stderr}"
            )

    return {
        "type": "pkg",
        "path": str(pkg_path),
        "packageId": pkg_id,
        "packageVersion": pkg_ver,
        "installLocation": install_loc,
        "xarFilesVerified": files_verified,
        "bomPathsCount": len(bom_entries),
        "bomFileCksumsVerified": cksums_verified,
        "bsdtarVerified": bsdtar_verified,
        "installerSigned": has_xar_sig,
        "app": app_summary,
    }


def collect_targets(inputs: Sequence[str]) -> List[Path]:
    targets: List[Path] = []
    for raw in inputs:
        p = Path(raw)
        if not p.exists():
            raise VerificationError(f"Path does not exist: {p}")
        if p.is_dir() and p.suffix == ".app":
            targets.append(p)
        elif p.is_dir():
            for child in sorted(p.iterdir()):
                if child.is_dir() and child.suffix == ".app":
                    targets.append(child)
                elif child.is_file() and child.suffix.lower() in (".zip", ".dmg", ".pkg"):
                    if "macos" in child.name.lower() or child.suffix.lower() in (".dmg", ".pkg"):
                        targets.append(child)
        elif p.is_file():
            targets.append(p)
    return targets


def verify_target(
    target: Path,
    expected_version: Optional[str] = None,
    expected_arch: Optional[str] = None,
    expected_bundle_id: str = "com.music.bitchord",
    require_developer_id: bool = False,
) -> Dict[str, Any]:
    if target.is_dir() and target.suffix == ".app":
        return verify_app_bundle(
            target,
            expected_version=expected_version,
            expected_arch=expected_arch,
            expected_bundle_id=expected_bundle_id,
            require_developer_id=require_developer_id,
        )
    sfx = target.suffix.lower()
    if sfx == ".zip":
        return verify_zip_artifact(
            target,
            expected_version=expected_version,
            expected_arch=expected_arch,
            expected_bundle_id=expected_bundle_id,
            require_developer_id=require_developer_id,
        )
    if sfx == ".dmg":
        return verify_dmg_artifact(
            target,
            expected_version=expected_version,
            expected_arch=expected_arch,
            expected_bundle_id=expected_bundle_id,
            require_developer_id=require_developer_id,
        )
    if sfx == ".pkg":
        return verify_pkg_artifact(
            target,
            expected_version=expected_version,
            expected_arch=expected_arch,
            expected_bundle_id=expected_bundle_id,
            require_developer_id=require_developer_id,
        )
    raise VerificationError(f"Unsupported macOS artifact type: {target}")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Verify macOS .app, .zip, .dmg, and .pkg release artifacts.")
    parser.add_argument("paths", nargs="+", help="Artifact file(s) or directory containing macOS artifacts")
    parser.add_argument("--expected-version", default=None, help="Expected Sonara version string")
    parser.add_argument("--expected-arch", choices=["arm64", "x64"], default=None, help="Expected Mach-O CPU arch")
    parser.add_argument("--bundle-id", default="com.music.bitchord", help="Expected CFBundleIdentifier")
    parser.add_argument(
        "--require-developer-id",
        action="store_true",
        help="Fail if artifacts are ad-hoc signed rather than Developer ID signed",
    )
    parser.add_argument("--json", action="store_true", help="Print full JSON report")
    args = parser.parse_args(argv)

    try:
        targets = collect_targets(args.paths)
        if not targets:
            raise VerificationError(f"No macOS artifacts (.app/.zip/.dmg/.pkg) found in: {args.paths}")

        results: List[Dict[str, Any]] = []
        for target in targets:
            res = verify_target(
                target,
                expected_version=args.expected_version,
                expected_arch=args.expected_arch,
                expected_bundle_id=args.bundle_id,
                require_developer_id=args.require_developer_id,
            )
            results.append(res)
            app_info = res.get("app", res)
            print(
                f"[OK] {res['type'].upper():4s} {target.name}: "
                f"arch={app_info['arch']} ({app_info['cputype']}) "
                f"ver={app_info['shortVersion']} sig={app_info['signatureKind']} "
                f"jars={app_info['jarsVerified']} files2={app_info['codeResourcesFilesVerified']}"
            )

        if args.json:
            print(json.dumps(results, indent=2))
        print(f"Verified {len(results)} macOS artifact(s) successfully.")
        return 0
    except VerificationError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
