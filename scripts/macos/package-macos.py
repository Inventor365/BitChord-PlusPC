#!/usr/bin/env python3
"""
Deterministic macOS DMG, PKG, and ZIP packager for BitChord.

Modes:
  1. Native macOS (sys.platform == "darwin", without --base-dir):
     - Builds BitChord.app via Gradle :desktopApp:createDistributable (or --app-bundle)
     - Signs BitChord.app with Developer ID Application (--options runtime --timestamp)
       if MACOS_SIGN_IDENTITY is set, else clean ad-hoc ('-') with documented notice
     - Packages .zip (ditto), .dmg (hdiutil create -format UDZO with /Applications symlink
       and populated Data Fork / mish / Master CRC-32s), and .pkg (pkgbuild + productbuild
       installing to /Applications/BitChord.app with normalized 0x78 0xda XAR TOC and
       stripped-header gzip 1f 8b 08 00 00 00 00 00 Payload)
     - Optionally notarizes & staples via xcrun notarytool + stapler
     - Runs scripts/macos/verify-macos-artifacts.py

  2. Cross-repack (--base-dir <dir>, Linux CI or macOS):
     - Updates BitChord.app (JARs, BitChord.cfg, Info.plist, _CodeSignature/CodeResources
       SHA-256 digests, and Mach-O LC_CODE_SIGNATURE CodeDirectory Special Slots -1/-3)
     - Deterministically writes .zip, updates base .dmg HFS+ partition in-place using the
       HFS+ allocation bitmap (verifying with fsck.hfsplus -fn, splitting modified runs
       into <= 2048-sector zlib chunks, and recomputing mish, Master, and Data Fork CRC-32s),
       and updates .pkg (raw zlib 0x78 0xda XAR streams, POSIX cksum Bom, stripped-header
       gzip Payload cpio)
     - Runs scripts/macos/verify-macos-artifacts.py
"""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import importlib.util
import os
import platform
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

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]

_verifier_spec = importlib.util.spec_from_file_location(
    "verify_macos_artifacts", SCRIPT_DIR / "verify-macos-artifacts.py"
)
assert _verifier_spec and _verifier_spec.loader
verifier = importlib.util.module_from_spec(_verifier_spec)
_verifier_spec.loader.exec_module(verifier)

DETERMINISTIC_UNIX_MTIME = 1767225600  # 2026-01-01T00:00:00Z
DETERMINISTIC_ZIP_DT = (2026, 1, 1, 0, 0, 0)


class PackagingError(Exception):
    """Raised when macOS packaging or cross-repacking fails."""


def zlib_compress_78da(data: bytes) -> bytes:
    comp = zlib.compressobj(9, zlib.DEFLATED, 15)
    out = comp.compress(data) + comp.flush()
    if out[:2] != b"\x78\xda":
        raise PackagingError(f"Expected 0x78 0xda zlib stream header, got {out[:2].hex()}")
    return out


def gzip_compress_stripped(data: bytes) -> bytes:
    out = gzip.compress(data, compresslevel=9, mtime=0)
    if out[:8] != verifier.STRIPPED_GZIP_PREFIX:
        raise PackagingError(f"Expected stripped gzip header, got {out[:8].hex(' ')}")
    return out


# ---------------------------------------------------------------------------
# .app metadata, CodeResources & Mach-O LC_CODE_SIGNATURE Special Slots -1/-3
# ---------------------------------------------------------------------------


def sync_app_jars(app_dir: Path, jars_dir: Optional[Path], explicit_jars: Sequence[Path]) -> Dict[str, str]:
    """Syncs updated JARs into Contents/app, returning {old_jar_name: new_jar_name} for renamed stems."""
    app_jars_dir = app_dir / "Contents" / "app"
    if not app_jars_dir.is_dir():
        raise PackagingError(f"Missing Contents/app directory in {app_dir}")

    incoming: List[Path] = []
    if jars_dir is not None:
        if not jars_dir.is_dir():
            raise PackagingError(f"--jars-dir does not exist: {jars_dir}")
        for p in sorted(jars_dir.glob("*.jar")):
            lower = p.name.lower()
            if any(bad in lower for bad in ("linux", "windows", "-win")):
                continue
            incoming.append(p)
    incoming.extend(explicit_jars)

    renamed: Dict[str, str] = {}
    for src_jar in incoming:
        if not src_jar.is_file():
            raise PackagingError(f"Replacement JAR not found: {src_jar}")
        matched_prefix = next(
            (pfx for pfx in ("desktopApp-", "desktopUi-", "shared-", "sharedUi-") if src_jar.name.startswith(pfx)),
            None,
        )
        if matched_prefix is not None:
            for old in sorted(app_jars_dir.glob(f"{matched_prefix}*.jar")):
                if old.name != src_jar.name:
                    renamed[old.name] = src_jar.name
                old.unlink()
        shutil.copy2(src_jar, app_jars_dir / src_jar.name)
    return renamed


def update_app_cfg_and_plist(
    app_dir: Path,
    version: str,
    bundle_id: str,
    listen_together_server: str,
    collab_server: str,
    collab_google_client_id: str,
    ota_url: str,
) -> None:
    contents = app_dir / "Contents"
    info_path = contents / "Info.plist"
    info = plistlib.loads(info_path.read_bytes())
    exec_name = str(info.get("CFBundleExecutable", "BitChord")).strip() or "BitChord"
    clean_ver = version.strip().lstrip("v")

    info["CFBundleIdentifier"] = bundle_id
    info["CFBundleShortVersionString"] = clean_ver
    info["CFBundleVersion"] = clean_ver
    info_path.write_bytes(plistlib.dumps(info, fmt=plistlib.FMT_XML, sort_keys=True))

    cfg_path = contents / "app" / f"{exec_name}.cfg"
    if not cfg_path.is_file():
        raise PackagingError(f"Missing {cfg_path}")

    app_jars = sorted(p.name for p in (contents / "app").glob("*.jar"))
    if not app_jars:
        raise PackagingError(f"No .jar files found in {contents / 'app'}")

    existing_lines = cfg_path.read_text(encoding="utf-8").splitlines()
    preserved_java_opts: List[str] = []
    main_class = "com.music.bitchord.desktop.MainKt"
    managed = (
        "-Dbitchord.version=",
        "-Dbitchord.listentogether.server=",
        "-Dbitchord.collab.server=",
        "-Dbitchord.collab.googleClientId=",
        "-Dbitchord.ota.url=",
    )
    for raw in existing_lines:
        s = raw.strip()
        if s.startswith("app.mainclass="):
            main_class = s.split("=", 1)[1].strip()
        elif s.startswith("java-options="):
            opt = s.split("=", 1)[1]
            if any(opt.startswith(m) for m in managed):
                if opt.startswith("-Dbitchord.ota.url=") and not ota_url:
                    preserved_java_opts.append(opt)
                elif opt.startswith("-Dbitchord.listentogether.server=") and not listen_together_server:
                    preserved_java_opts.append(opt)
                elif opt.startswith("-Dbitchord.collab.server=") and not collab_server:
                    preserved_java_opts.append(opt)
                elif opt.startswith("-Dbitchord.collab.googleClientId=") and not collab_google_client_id:
                    preserved_java_opts.append(opt)
                continue
            preserved_java_opts.append(opt)

    out_lines = ["[Application]", f"app.mainclass={main_class}", f"app.version={clean_ver}"]
    for jar_name in app_jars:
        out_lines.append(f"app.classpath=$APPDIR/{jar_name}")
    out_lines.extend(["", "[JavaOptions]"])
    for opt in preserved_java_opts:
        out_lines.append(f"java-options={opt}")
    out_lines.append(f"java-options=-Dbitchord.version={clean_ver}")
    if ota_url:
        out_lines.append(f"java-options=-Dbitchord.ota.url={ota_url}")
    if listen_together_server:
        out_lines.append(f"java-options=-Dbitchord.listentogether.server={listen_together_server.rstrip('/')}")
    eff_collab = (collab_server or listen_together_server).rstrip("/")
    if eff_collab:
        out_lines.append(f"java-options=-Dbitchord.collab.server={eff_collab}")
    if collab_google_client_id:
        out_lines.append(f"java-options=-Dbitchord.collab.googleClientId={collab_google_client_id}")
    cfg_path.write_text("\n".join(out_lines) + "\n", encoding="utf-8")


def rebuild_code_resources(app_dir: Path) -> bytes:
    contents = app_dir / "Contents"
    info = plistlib.loads((contents / "Info.plist").read_bytes())
    exec_name = str(info.get("CFBundleExecutable", "BitChord")).strip() or "BitChord"

    code_res_dir = contents / "_CodeSignature"
    code_res_dir.mkdir(parents=True, exist_ok=True)
    code_res_path = code_res_dir / "CodeResources"

    existing: Dict[str, Any] = {}
    if code_res_path.is_file():
        try:
            existing = plistlib.loads(code_res_path.read_bytes())
        except Exception:
            existing = {}

    include_v1 = "files" in existing
    files_v1: Dict[str, Any] = {}
    files2: Dict[str, Any] = {}
    excluded = {"Info.plist", "PkgInfo", f"MacOS/{exec_name}", "_CodeSignature/CodeResources"}

    for root_str, dirs, filenames in os.walk(contents, followlinks=False):
        dirs.sort()
        filenames.sort()
        rp = Path(root_str)
        rel_dir = rp.relative_to(contents).as_posix()
        if rel_dir == "_CodeSignature" or rel_dir.startswith("_CodeSignature/"):
            continue
        kept_dirs: List[str] = []
        for d in dirs:
            dp = rp / d
            rel = dp.relative_to(contents).as_posix()
            if rel == "_CodeSignature":
                continue
            if dp.is_symlink():
                lt = os.readlink(dp)
                files2[rel] = {"symlink": lt}
                if include_v1:
                    files_v1[rel] = {"symlink": lt}
            else:
                kept_dirs.append(d)
        dirs[:] = kept_dirs

        for fname in filenames:
            if fname == ".DS_Store":
                (rp / fname).unlink(missing_ok=True)
                continue
            fp = rp / fname
            rel = fp.relative_to(contents).as_posix()
            if rel in excluded:
                continue
            if fp.is_symlink():
                lt = os.readlink(fp)
                files2[rel] = {"symlink": lt}
                if include_v1:
                    files_v1[rel] = {"symlink": lt}
            elif fp.is_file():
                raw = fp.read_bytes()
                sha256_dig = hashlib.sha256(raw).digest()
                sha1_dig = hashlib.sha1(raw).digest()
                prev = existing.get("files2", {}).get(rel)
                e2: Dict[str, Any] = {"hash2": sha256_dig}
                if isinstance(prev, dict) and "hash" in prev:
                    e2["hash"] = sha1_dig
                if isinstance(prev, dict) and prev.get("optional"):
                    e2["optional"] = True
                files2[rel] = e2
                if include_v1 and not rel.startswith(("MacOS/", "runtime/")):
                    files_v1[rel] = sha1_dig

    out_dict: Dict[str, Any] = {
        "files2": files2,
        "rules2": existing.get(
            "rules2",
            {
                ".*\\.dSYM($|/)": {"weight": 11.0},
                "^(.*/)?\\.DS_Store$": {"omit": True, "weight": 2000.0},
                "^Info\\.plist$": {"omit": True, "weight": 20.0},
                "^PkgInfo$": {"omit": True, "weight": 20.0},
                "^embedded\\.provisionprofile$": {"weight": 20.0},
                "^version\\.plist$": {"weight": 20.0},
            },
        ),
    }
    if include_v1:
        out_dict["files"] = files_v1
    if "rules" in existing or include_v1:
        out_dict["rules"] = existing.get(
            "rules",
            {
                "^Resources/": True,
                "^Resources/.*\\.lproj/": {"optional": True, "weight": 1000.0},
                "^Resources/.*\\.lproj/locversion.plist$": {"omit": True, "weight": 1100.0},
            },
        )

    encoded = plistlib.dumps(out_dict, fmt=plistlib.FMT_XML, sort_keys=True)
    code_res_path.write_bytes(encoded)
    return encoded


def patch_macho_code_signature_slots(
    exec_path: Path, info_plist_bytes: bytes, code_resources_bytes: bytes
) -> None:
    buf = bytearray(exec_path.read_bytes())
    if len(buf) < 32 or struct.unpack("<I", buf[:4])[0] != verifier.MH_MAGIC_64:
        raise PackagingError(f"{exec_path}: expected 64-bit little-endian Mach-O")

    ncmds = struct.unpack("<I", buf[16:20])[0]
    sig_off: Optional[int] = None
    pos = 32
    for _ in range(ncmds):
        cmd, cmdsize = struct.unpack("<II", buf[pos : pos + 8])
        if cmd == verifier.LC_CODE_SIGNATURE:
            _, _, sig_off, _ = struct.unpack("<IIII", buf[pos : pos + 16])
            break
        pos += cmdsize

    if sig_off is None:
        rcodesign = verifier.find_host_tool(["rcodesign"])
        if rcodesign:
            subprocess.run([rcodesign, "sign", str(exec_path.parents[2])], check=True)
            return
        raise PackagingError(f"{exec_path}: missing LC_CODE_SIGNATURE and rcodesign unavailable")

    sb_magic, _sb_len, sb_count = struct.unpack(">III", buf[sig_off : sig_off + 12])
    if sb_magic != verifier.CSMAGIC_EMBEDDED_SIGNATURE:
        raise PackagingError(f"{exec_path}: invalid CS_SuperBlob magic 0x{sb_magic:08x}")

    patched = 0
    for i in range(sb_count):
        slot_type, rel_off = struct.unpack(">II", buf[sig_off + 12 + i * 8 : sig_off + 20 + i * 8])
        abs_off = sig_off + rel_off
        b_magic, b_len = struct.unpack(">II", buf[abs_off : abs_off + 8])
        if slot_type == verifier.CSSLOT_CODEDIRECTORY or (
            verifier.CSSLOT_ALT_CODEDIRECTORY_BASE <= slot_type <= verifier.CSSLOT_ALT_CODEDIRECTORY_MAX
        ):
            (
                _m, _l, _v, cd_flags, hash_offset, _id_off, n_special, _n_code, _clim, hash_size, hash_type, _pl, _pg, _sp
            ) = struct.unpack(">9I4BI", buf[abs_off : abs_off + 44])
            if n_special < 3:
                raise PackagingError(f"{exec_path}: CodeDirectory nSpecialSlots={n_special} < 3")
            struct.pack_into(">I", buf, abs_off + 12, cd_flags | verifier.CS_ADHOC)
            s1 = abs_off + hash_offset - 1 * hash_size
            s3 = abs_off + hash_offset - 3 * hash_size
            buf[s1 : s1 + hash_size] = verifier._cd_hash_bytes(info_plist_bytes, hash_type, hash_size)
            buf[s3 : s3 + hash_size] = verifier._cd_hash_bytes(code_resources_bytes, hash_type, hash_size)
            patched += 1
        elif slot_type == verifier.CSSLOT_SIGNATURESLOT and b_magic == verifier.CSMAGIC_BLOBWRAPPER and b_len > 8:
            struct.pack_into(">II", buf, abs_off, verifier.CSMAGIC_BLOBWRAPPER, 8)
            buf[abs_off + 8 : abs_off + b_len] = b"\x00" * (b_len - 8)

    if patched == 0:
        raise PackagingError(f"{exec_path}: no CodeDirectory slots found")
    exec_path.write_bytes(bytes(buf))
    os.chmod(exec_path, 0o755)


def write_deterministic_zip(app_dir: Path, out_zip: Path) -> None:
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    base_parent = app_dir.parent
    paths: List[Path] = []
    for root_str, dirs, files in os.walk(app_dir, followlinks=False):
        dirs.sort()
        files.sort()
        rp = Path(root_str)
        paths.append(rp)
        kept: List[str] = []
        for d in dirs:
            dp = rp / d
            if dp.is_symlink():
                paths.append(dp)
            else:
                kept.append(d)
        dirs[:] = kept
        for f in files:
            if f != ".DS_Store":
                paths.append(rp / f)

    with zipfile.ZipFile(out_zip, "w") as zf:
        for p in paths:
            rel = p.relative_to(base_parent).as_posix()
            if p.is_symlink():
                zi = zipfile.ZipInfo(rel, date_time=DETERMINISTIC_ZIP_DT)
                zi.create_system = 3
                zi.external_attr = (stat.S_IFLNK | 0o755) << 16
                zf.writestr(zi, os.readlink(p).encode("utf-8"), compress_type=zipfile.ZIP_STORED)
            elif p.is_dir():
                zi = zipfile.ZipInfo(rel.rstrip("/") + "/", date_time=DETERMINISTIC_ZIP_DT)
                zi.create_system = 3
                zi.external_attr = ((stat.S_IFDIR | 0o755) << 16) | 0x10
                zf.writestr(zi, b"", compress_type=zipfile.ZIP_STORED)
            else:
                perms = 0o755 if (p.stat().st_mode & stat.S_IXUSR) else 0o644
                zi = zipfile.ZipInfo(rel, date_time=DETERMINISTIC_ZIP_DT)
                zi.create_system = 3
                zi.external_attr = (stat.S_IFREG | perms) << 16
                zf.writestr(zi, p.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


# ---------------------------------------------------------------------------
# In-place HFS+ partition updater + UDIF (.dmg) repacker
# ---------------------------------------------------------------------------


def _bitmap_is_set(bitmap: bytearray, blk: int) -> bool:
    return bool(bitmap[blk >> 3] & (0x80 >> (blk & 7)))


def _bitmap_set_range(bitmap: bytearray, start: int, count: int, used: bool) -> None:
    for b in range(start, start + count):
        mask = 0x80 >> (b & 7)
        if used:
            bitmap[b >> 3] |= mask
        else:
            bitmap[b >> 3] &= ~mask


def _bitmap_alloc_contiguous(bitmap: bytearray, total_blocks: int, needed: int, hint: int = 1) -> int:
    if needed == 0:
        return 0
    for search_start in (hint, 1):
        run_start = -1
        run_len = 0
        # Never touch block 0 or total_blocks - 1 (Primary & Alternate VolumeHeader)
        for b in range(max(1, search_start), total_blocks - 1):
            if not _bitmap_is_set(bitmap, b):
                if run_len == 0:
                    run_start = b
                run_len += 1
                if run_len == needed:
                    _bitmap_set_range(bitmap, run_start, needed, True)
                    return run_start
            else:
                run_len = 0
    raise PackagingError(
        f"Base HFS+ partition does not have {needed} contiguous free blocks (totalBlocks={total_blocks})"
    )


def update_hfsplus_partition_inplace(
    hfs_image: bytes, app_dir: Path, renamed_jars: Dict[str, str]
) -> bytes:
    """
    Modifies an existing HFS+ partition image in place:
    walks the Catalog B-Tree leaf chain, locates BitChord.app files whose bytes
    changed (Info.plist, BitChord.cfg, desktopApp/shared JARs, CodeResources,
    MacOS/BitChord), updates blocks via the HFS+ allocation bitmap, updates
    Primary & Alternate VolumeHeaders, and validates via fsck.hfsplus -fn.
    """
    vol = bytearray(hfs_image)
    vh = bytearray(vol[1024:1536])
    sig = bytes(vh[:2])
    if sig not in (b"H+", b"HX"):
        raise PackagingError(f"Unexpected HFS+ VolumeHeader signature: {sig!r}")

    block_size, total_blocks, _orig_free, next_alloc = struct.unpack(">IIII", vh[40:56])

    # Load AllocationFile bitmap (supports up to 8 extents)
    alloc_fork = bytes(vh[112:192])
    alloc_logical = struct.unpack(">Q", alloc_fork[:8])[0]
    alloc_extents: List[Tuple[int, int]] = []
    for i in range(8):
        sb, bc = struct.unpack(">II", alloc_fork[16 + i * 8 : 24 + i * 8])
        if bc:
            alloc_extents.append((sb, bc))
    bitmap = bytearray()
    for sb, bc in alloc_extents:
        bitmap.extend(vol[sb * block_size : (sb + bc) * block_size])

    # Map Catalog logical offsets -> volume physical offsets so leaf updates write back in place
    cat_fork = bytes(vh[272:352])
    cat_logical = struct.unpack(">Q", cat_fork[:8])[0]
    cat_extents: List[Tuple[int, int]] = []
    for i in range(8):
        sb, bc = struct.unpack(">II", cat_fork[16 + i * 8 : 24 + i * 8])
        if bc:
            cat_extents.append((sb, bc))

    cat_buf = bytearray()
    for sb, bc in cat_extents:
        cat_buf.extend(vol[sb * block_size : (sb + bc) * block_size])

    node_size = struct.unpack(">H", cat_buf[14 + 18 : 14 + 20])[0]
    first_leaf = struct.unpack(">I", cat_buf[14 + 10 : 14 + 14])[0]

    # First pass: collect folder hierarchy (folder_id -> (parent_id, name))
    folders: Dict[int, Tuple[int, str]] = {}
    leaf_idx = first_leaf
    while leaf_idx != 0:
        n_off = leaf_idx * node_size
        f_link, _b_link, kind, _h, num_recs, _ = struct.unpack(">IIbBHH", cat_buf[n_off : n_off + 14])
        if kind != -1:
            raise PackagingError(f"Expected HFS+ leaf node at {leaf_idx}, got {kind}")
        offs = [
            struct.unpack(">H", cat_buf[n_off + node_size - 2 * (i + 1) : n_off + node_size - 2 * i])[0]
            for i in range(num_recs + 1)
        ]
        for i in range(num_recs):
            rec = cat_buf[n_off + offs[i] : n_off + offs[i + 1]]
            klen = struct.unpack(">H", rec[:2])[0]
            pid, nchars = struct.unpack(">IH", rec[2:8])
            nm = bytes(rec[8 : 8 + nchars * 2]).decode("utf-16-be", errors="replace")
            voff = 2 + klen + ((2 + klen) & 1)
            rtype = struct.unpack(">h", rec[voff : voff + 2])[0]
            if rtype == 1:
                fid = struct.unpack(">I", rec[voff + 8 : voff + 12])[0]
                folders[fid] = (pid, nm)
        leaf_idx = f_link

    def resolve_rel_path(parent_id: int, name: str) -> Optional[str]:
        parts = [name]
        cur = parent_id
        guard = 0
        while cur != 2:
            if cur not in folders or guard > 128:
                return None
            pid, fnm = folders[cur]
            if pid == 1:
                break
            parts.append(fnm)
            cur = pid
            guard += 1
        return "/".join(reversed(parts))

    # Build lookup of desired regular file bytes in BitChord.app
    desired_files: Dict[str, bytes] = {}
    for root_str, _dirs, files in os.walk(app_dir, followlinks=False):
        rp = Path(root_str)
        for fn in files:
            fp = rp / fn
            if not fp.is_symlink() and fp.is_file():
                rel = "BitChord.app/" + fp.relative_to(app_dir).as_posix()
                desired_files[rel] = fp.read_bytes()

    # Second pass: update any modified file's extents & logicalSize in place
    updated_files = 0
    leaf_idx = first_leaf
    while leaf_idx != 0:
        n_off = leaf_idx * node_size
        f_link, _b_link, _kind, _h, num_recs, _ = struct.unpack(">IIbBHH", cat_buf[n_off : n_off + 14])
        offs = [
            struct.unpack(">H", cat_buf[n_off + node_size - 2 * (i + 1) : n_off + node_size - 2 * i])[0]
            for i in range(num_recs + 1)
        ]
        for i in range(num_recs):
            rec_abs = n_off + offs[i]
            rec_end = n_off + offs[i + 1]
            rec = cat_buf[rec_abs:rec_end]
            klen = struct.unpack(">H", rec[:2])[0]
            pid, nchars = struct.unpack(">IH", rec[2:8])
            nm = bytes(rec[8 : 8 + nchars * 2]).decode("utf-16-be", errors="replace")
            voff = 2 + klen + ((2 + klen) & 1)
            rtype = struct.unpack(">h", rec[voff : voff + 2])[0]
            if rtype != 2:
                continue

            file_mode = struct.unpack(">H", rec[voff + 42 : voff + 44])[0]
            if (file_mode & 0o170000) == stat.S_IFLNK:
                continue

            rel_path = resolve_rel_path(pid, nm)
            if rel_path is None:
                continue
            target_rel = rel_path
            if target_rel not in desired_files and nm in renamed_jars:
                candidate_rel = os.path.dirname(rel_path) + "/" + renamed_jars[nm]
                if candidate_rel in desired_files and len(renamed_jars[nm]) == len(nm):
                    # Same-length rename in place
                    new_u16 = renamed_jars[nm].encode("utf-16-be")
                    cat_buf[rec_abs + 8 : rec_abs + 8 + len(new_u16)] = new_u16
                    target_rel = candidate_rel

            if target_rel not in desired_files:
                continue

            new_bytes = desired_files[target_rel]
            dfork_off = rec_abs + voff + 88
            old_fork = bytes(cat_buf[dfork_off : dfork_off + 80])
            old_bytes = verifier._read_fork_bytes(vol, block_size, old_fork)
            if old_bytes == new_bytes:
                continue

            # Free old extents in bitmap & zero old blocks
            old_clump = struct.unpack(">I", old_fork[8:12])[0] or 65536
            for ext_i in range(8):
                osb, obc = struct.unpack(">II", old_fork[16 + ext_i * 8 : 24 + ext_i * 8])
                if obc:
                    _bitmap_set_range(bitmap, osb, obc, False)
                    vol[osb * block_size : (osb + obc) * block_size] = b"\x00" * (obc * block_size)

            needed_blks = (len(new_bytes) + block_size - 1) // block_size
            new_sb = _bitmap_alloc_contiguous(bitmap, total_blocks, needed_blks, hint=next_alloc)
            if needed_blks > 0:
                padded = new_bytes + b"\x00" * (needed_blks * block_size - len(new_bytes))
                vol[new_sb * block_size : (new_sb + needed_blks) * block_size] = padded

            new_fork = struct.pack(">QII", len(new_bytes), old_clump, needed_blks)
            new_fork += struct.pack(">II", new_sb, needed_blks) if needed_blks > 0 else (b"\x00" * 8)
            new_fork += b"\x00" * 56
            cat_buf[dfork_off : dfork_off + 80] = new_fork
            updated_files += 1
        leaf_idx = f_link

    # Write updated Catalog extents and AllocationFile bitmap extents back to volume
    c_cursor = 0
    for sb, bc in cat_extents:
        span = bc * block_size
        vol[sb * block_size : (sb + bc) * block_size] = cat_buf[c_cursor : c_cursor + span]
        c_cursor += span

    b_cursor = 0
    for sb, bc in alloc_extents:
        span = bc * block_size
        vol[sb * block_size : (sb + bc) * block_size] = bitmap[b_cursor : b_cursor + span]
        b_cursor += span

    # Recompute exact freeBlocks across 0..total_blocks-1 and update Primary & Alternate VolumeHeaders
    used_blocks = sum(1 for b in range(total_blocks) if _bitmap_is_set(bitmap, b))
    free_blocks = total_blocks - used_blocks
    struct.pack_into(">I", vh, 48, free_blocks)
    vol[1024:1536] = vh
    alt_vh_off = len(vol) - 1024
    if vol[alt_vh_off : alt_vh_off + 2] == sig:
        vol[alt_vh_off : alt_vh_off + 512] = vh

    fsck_tool = verifier.find_host_tool(["fsck.hfsplus", "fsck_hfs"])
    if fsck_tool:
        with tempfile.NamedTemporaryFile(suffix=".hfs", delete=False) as tf:
            tf.write(vol)
            tmp_hfs = tf.name
        try:
            chk = subprocess.run([fsck_tool, "-fn", tmp_hfs], capture_output=True, text=True)
            if chk.returncode != 0:
                raise PackagingError(f"fsck.hfsplus failed after in-place update:\n{chk.stdout}\n{chk.stderr}")
        finally:
            os.unlink(tmp_hfs)

    print(f"  Updated {updated_files} modified file(s) in HFS+ partition in-place (freeBlocks={free_blocks}).")
    return bytes(vol)


def repack_dmg_inplace(base_dmg: Path, app_dir: Path, renamed_jars: Dict[str, str], out_dmg: Path) -> None:
    dmg_bytes = base_dmg.read_bytes()
    koly = bytearray(dmg_bytes[-512:])
    if koly[:4] != verifier.KOLY_MAGIC:
        raise PackagingError(f"{base_dmg}: invalid koly trailer")

    xml_off, xml_len = struct.unpack(">QQ", koly[216:232])
    plist = plistlib.loads(dmg_bytes[xml_off : xml_off + xml_len])
    blkx_list = plist["resource-fork"]["blkx"]

    new_data_fork = bytearray()
    mish_crcs: List[int] = []

    for entry in blkx_list:
        name = str(entry.get("Name") or entry.get("CFName") or "")
        mish = entry["Data"]
        m_sig, m_ver, m_first_sec, m_sec_cnt, m_data_off, m_bufs, m_desc = struct.unpack(">4sIQQQII", mish[:40])
        m_chk_type, m_chk_bits, _ = struct.unpack(">III", mish[64:76])
        n_runs = struct.unpack(">I", mish[200:204])[0]
        is_hfs = ("Apple_HFS" in name) or (len(blkx_list) == 1)

        if not is_hfs:
            # Copy non-HFS partition runs verbatim while adjusting CompOffset into new_data_fork
            new_runs: List[bytes] = []
            run_crc = 0
            for r in range(n_runs):
                roff = 204 + r * 40
                rtype, rcomm, rsec, rcnt, rcoff, rclen = struct.unpack(">IIQQQQ", mish[roff : roff + 40])
                if rtype == verifier.BLKX_RUN_TERM:
                    new_runs.append(struct.pack(">IIQQQQ", rtype, rcomm, rsec, rcnt, len(new_data_fork), 0))
                    break
                if rtype in (verifier.BLKX_RUN_ZERO, verifier.BLKX_RUN_IGNORE, verifier.BLKX_RUN_COMMENT) or rclen == 0:
                    new_runs.append(struct.pack(">IIQQQQ", rtype, rcomm, rsec, rcnt, len(new_data_fork), 0))
                    continue
                cslice = dmg_bytes[m_data_off + rcoff : m_data_off + rcoff + rclen]
                uncomp = zlib.decompress(cslice) if rtype == verifier.BLKX_RUN_ZLIB else cslice
                run_crc = zlib.crc32(uncomp, run_crc) & 0xFFFFFFFF
                new_off = len(new_data_fork)
                new_data_fork.extend(cslice)
                new_runs.append(struct.pack(">IIQQQQ", rtype, rcomm, rsec, rcnt, new_off, len(cslice)))

            mhdr = bytearray(mish[:204])
            struct.pack_into(">Q", mhdr, 24, 0)
            if m_chk_type == verifier.UDIF_CHECKSUM_CRC32:
                struct.pack_into(">III", mhdr, 64, verifier.UDIF_CHECKSUM_CRC32, 32, run_crc)
                mish_crcs.append(run_crc)
            struct.pack_into(">I", mhdr, 200, len(new_runs))
            entry["Data"] = bytes(mhdr) + b"".join(new_runs)
        else:
            # Decompress HFS+ partition, apply in-place file updates, and split runs <= 2048 sectors
            part_buf = bytearray(m_sec_cnt * 512)
            for r in range(n_runs):
                roff = 204 + r * 40
                rtype, _, rsec, rcnt, rcoff, rclen = struct.unpack(">IIQQQQ", mish[roff : roff + 40])
                if rtype == verifier.BLKX_RUN_TERM:
                    break
                if rtype in (verifier.BLKX_RUN_ZERO, verifier.BLKX_RUN_IGNORE, verifier.BLKX_RUN_COMMENT) or rclen == 0:
                    continue
                cslice = dmg_bytes[m_data_off + rcoff : m_data_off + rcoff + rclen]
                uncomp = zlib.decompress(cslice) if rtype == verifier.BLKX_RUN_ZLIB else cslice
                part_buf[rsec * 512 : (rsec + rcnt) * 512] = uncomp

            updated_hfs = update_hfsplus_partition_inplace(bytes(part_buf), app_dir, renamed_jars)

            # Read updated AllocationFile bitmap so unallocated HFS+ blocks emit BLKX_RUN_IGNORE
            vh = updated_hfs[1024:1536]
            blk_sz, tot_blks = struct.unpack(">II", vh[40:48])
            sec_per_blk = blk_sz // 512
            bm = verifier._read_fork_bytes(updated_hfs, blk_sz, vh[112:192])

            new_runs = []
            run_crc = 0
            b = 0
            while b < tot_blks:
                allocated = bool(bm[b >> 3] & (0x80 >> (b & 7)))
                b_end = b + 1
                while b_end < tot_blks and bool(bm[b_end >> 3] & (0x80 >> (b_end & 7))) == allocated:
                    b_end += 1
                sec_start = b * sec_per_blk
                sec_total = (b_end - b) * sec_per_blk
                if not allocated:
                    new_runs.append(
                        struct.pack(">IIQQQQ", verifier.BLKX_RUN_IGNORE, 0, sec_start, sec_total, len(new_data_fork), 0)
                    )
                else:
                    s_cur = sec_start
                    s_rem = sec_total
                    while s_rem > 0:
                        chunk_s = min(s_rem, verifier.MAX_ZLIB_RUN_SECTORS)
                        raw_chunk = updated_hfs[s_cur * 512 : (s_cur + chunk_s) * 512]
                        comp_chunk = zlib_compress_78da(raw_chunk)
                        run_crc = zlib.crc32(raw_chunk, run_crc) & 0xFFFFFFFF
                        coff = len(new_data_fork)
                        if len(comp_chunk) >= len(raw_chunk):
                            r_type, payload = verifier.BLKX_RUN_RAW, raw_chunk
                        else:
                            r_type, payload = verifier.BLKX_RUN_ZLIB, comp_chunk
                        new_data_fork.extend(payload)
                        new_runs.append(
                            struct.pack(">IIQQQQ", r_type, 0, s_cur, chunk_s, coff, len(payload))
                        )
                        s_cur += chunk_s
                        s_rem -= chunk_s
                b = b_end

            if tot_blks * sec_per_blk < m_sec_cnt:
                rem_s = m_sec_cnt - tot_blks * sec_per_blk
                new_runs.append(
                    struct.pack(">IIQQQQ", verifier.BLKX_RUN_IGNORE, 0, tot_blks * sec_per_blk, rem_s, len(new_data_fork), 0)
                )
            new_runs.append(struct.pack(">IIQQQQ", verifier.BLKX_RUN_TERM, 0, m_sec_cnt, 0, len(new_data_fork), 0))

            mhdr = bytearray(mish[:204])
            struct.pack_into(">QI", mhdr, 24, 0, max(2056, m_bufs))
            struct.pack_into(">III", mhdr, 64, verifier.UDIF_CHECKSUM_CRC32, 32, run_crc)
            struct.pack_into(">I", mhdr, 200, len(new_runs))
            mish_crcs.append(run_crc)
            entry["Data"] = bytes(mhdr) + b"".join(new_runs)

    # Drop optional ASR cSum/nsiz caches if present so DiskImages validates mish/Master/DataFork CRCs cleanly
    plist["resource-fork"].pop("cSum", None)
    plist["resource-fork"].pop("nsiz", None)

    new_xml = plistlib.dumps(plist, fmt=plistlib.FMT_XML, sort_keys=True)
    df_crc = zlib.crc32(new_data_fork) & 0xFFFFFFFF
    master_crc = zlib.crc32(b"".join(struct.pack(">I", c) for c in mish_crcs)) & 0xFFFFFFFF

    struct.pack_into(">QQQQ", koly, 16, 0, 0, len(new_data_fork), 0)
    struct.pack_into(">Q", koly, 48, 0)
    struct.pack_into(">III", koly, 80, verifier.UDIF_CHECKSUM_CRC32, 32, df_crc)
    struct.pack_into(">QQ", koly, 216, len(new_data_fork), len(new_xml))
    koly[232:352] = b"\x00" * 120  # clear any stale DMG code-signature pointer
    struct.pack_into(">III", koly, 352, verifier.UDIF_CHECKSUM_CRC32, 32, master_crc)

    out_dmg.parent.mkdir(parents=True, exist_ok=True)
    with open(out_dmg, "wb") as f:
        f.write(new_data_fork)
        f.write(new_xml)
        f.write(koly)


# ---------------------------------------------------------------------------
# Deterministic .pkg (XAR + BOMStore + PackageInfo + stripped-gzip odc Payload)
# ---------------------------------------------------------------------------


def build_odc_cpio_and_bom(payload_root: Path, mtime: int = DETERMINISTIC_UNIX_MTIME) -> Tuple[bytes, bytes, int, int]:
    next_id = 1
    path_ids: Dict[Path, int] = {payload_root: 1}
    child_counts: Dict[Path, int] = {}
    nodes: List[Path] = [payload_root]

    def collect(cur: Path) -> None:
        nonlocal next_id
        kids = sorted([p for p in cur.iterdir() if p.name != ".DS_Store"], key=lambda p: p.name)
        child_counts[cur] = len(kids)
        for k in kids:
            next_id += 1
            path_ids[k] = next_id
            nodes.append(k)
            if k.is_dir() and not k.is_symlink():
                collect(k)

    collect(payload_root)

    cpio_parts: List[bytes] = []
    bom_items: List[Tuple[int, str, int, bytes]] = []
    total_512 = 0
    total_reg_bytes = 0

    for ino, node in enumerate(nodes, start=1):
        my_id = path_ids[node]
        if node == payload_root:
            rel_cpio, parent_id, bname = ".", 0, "."
        else:
            rel_cpio = "./" + node.relative_to(payload_root).as_posix()
            parent_id = path_ids[node.parent]
            bname = node.name
        uid, gid = 0, 80

        if node.is_symlink():
            tb = os.readlink(node).encode("utf-8")
            mode, nlink, data_b = stat.S_IFLNK | 0o755, 1, tb
            total_512 += (len(tb) + 511) // 512
            lnul = tb + b"\x00"
            rec_b = (
                struct.pack(">BBHHIIIIBII", 3, 1, 15, mode, uid, gid, mtime, len(tb), 1, verifier.posix_cksum(tb), len(lnul))
                + lnul
                + (b"\x00" * 8)
            )
        elif node.is_dir():
            kids_n = child_counts.get(node, 0)
            mode, nlink, data_b = stat.S_IFDIR | 0o755, kids_n + 2, b""
            dsize = 32 * (kids_n + 2)
            if node != payload_root:
                total_512 += (dsize + 511) // 512
            rec_b = struct.pack(">BBHHIIIIBII", 2, 1, 15, mode, uid, gid, mtime, dsize, 1, 0, 0)
        else:
            rb = node.read_bytes()
            perms = 0o755 if (node.stat().st_mode & stat.S_IXUSR) else 0o644
            mode, nlink, data_b = stat.S_IFREG | perms, 1, rb
            total_reg_bytes += len(rb)
            total_512 += (len(rb) + 511) // 512
            rec_b = struct.pack(
                ">BBHHIIIIBII", 1, 1, 15, mode, uid, gid, mtime, len(rb), 1, verifier.posix_cksum(rb), 0
            ) + (b"\x00" * 4)

        nnul = rel_cpio.encode("utf-8") + b"\x00"
        hdr = (
            f"070707{0:06o}{ino & 0o777777:06o}{mode:06o}{uid:06o}{gid:06o}"
            f"{nlink & 0o777777:06o}{0:06o}{mtime:011o}{len(nnul):06o}{len(data_b):011o}"
        ).encode("ascii")
        cpio_parts.extend((hdr, nnul))
        if data_b:
            cpio_parts.append(data_b)
        bom_items.append((parent_id, bname, my_id, rec_b))

    tn = b"TRAILER!!!\x00"
    cpio_parts.extend(
        (
            f"070707{0:06o}{0:06o}{0:06o}{0:06o}{0:06o}{1:06o}{0:06o}{0:011o}{len(tn):06o}{0:011o}".encode("ascii"),
            tn,
        )
    )
    cpio_raw = b"".join(cpio_parts)
    if len(cpio_raw) % 512:
        cpio_raw += b"\x00" * (512 - (len(cpio_raw) % 512))

    bom_items.sort(key=lambda x: (x[0], x[1].encode("utf-8")))
    bom_body = bytearray(512)
    ptrs: List[Tuple[int, int]] = [(0, 0)]

    def alloc_blk(payload: bytes) -> int:
        if len(bom_body) % 16:
            bom_body.extend(b"\x00" * (16 - (len(bom_body) % 16)))
        addr = len(bom_body)
        bom_body.extend(payload)
        ptrs.append((addr, len(payload)))
        return len(ptrs) - 1

    def make_tree(pairs: List[Tuple[int, int]], bsz: int = 4096) -> int:
        cap = max(1, (bsz - 12) // 8)
        if not pairs:
            lf = alloc_blk(struct.pack(">HHII", 1, 0, 0, 0))
            return alloc_blk(struct.pack(">4sIIIIB", b"tree", 1, lf, bsz, 0, 0))
        chunks = [pairs[i : i + cap] for i in range(0, len(pairs), cap)]
        lids = [alloc_blk(bytes(12 + 8 * len(ch))) for ch in chunks]
        for idx_l, (lid, ch) in enumerate(zip(lids, chunks)):
            fwd = lids[idx_l + 1] if idx_l + 1 < len(lids) else 0
            bwd = lids[idx_l - 1] if idx_l > 0 else 0
            buf = bytearray(struct.pack(">HHII", 1, len(ch), fwd, bwd))
            for i0, i1 in ch:
                buf.extend(struct.pack(">II", i0, i1))
            addr = ptrs[lid][0]
            bom_body[addr : addr + len(buf)] = buf
        cur = [(lid, ch[0][1]) for lid, ch in zip(lids, chunks)]
        while len(cur) > 1:
            bchunks = [cur[i : i + cap] for i in range(0, len(cur), cap)]
            bids = [alloc_blk(bytes(12 + 8 * len(bc))) for bc in bchunks]
            for idx_b, (bid, bc) in enumerate(zip(bids, bchunks)):
                fwd = bids[idx_b + 1] if idx_b + 1 < len(bids) else 0
                bwd = bids[idx_b - 1] if idx_b > 0 else 0
                buf = bytearray(struct.pack(">HHII", 0, len(bc), fwd, bwd))
                for cid, kid in bc:
                    buf.extend(struct.pack(">II", cid, kid))
                addr = ptrs[bid][0]
                bom_body[addr : addr + len(buf)] = buf
            cur = [(bid, bc[0][1]) for bid, bc in zip(bids, bchunks)]
        return alloc_blk(struct.pack(">4sIIIIB", b"tree", 1, cur[0][0], bsz, len(pairs), 0))

    bom_info = alloc_blk(struct.pack(">IIIIIII", 1, len(bom_items) + 1, 1, 0, 0, total_reg_bytes & 0xFFFFFFFF, 0))
    path_pairs: List[Tuple[int, int]] = []
    hl_pairs: List[Tuple[int, int]] = []
    for pid, bname, mid, rec_b in bom_items:
        rblk = alloc_blk(rec_b)
        p1 = alloc_blk(struct.pack(">II", mid, rblk))
        fk = alloc_blk(struct.pack(">I", pid) + bname.encode("utf-8") + b"\x00")
        path_pairs.append((p1, fk))
        hl_pairs.append((alloc_blk(b"\x00" * 64), alloc_blk(struct.pack(">I", rblk))))

    vars_entries = [
        ("BomInfo", bom_info),
        ("Paths", make_tree(path_pairs, 4096)),
        ("HLIndex", make_tree(hl_pairs, 4096)),
        ("VIndex", make_tree([], 128)),
        ("Size64", make_tree([], 128)),
    ]
    vbuf = bytearray(struct.pack(">I", len(vars_entries)))
    for vn, vb in vars_entries:
        vnb = vn.encode("ascii")
        vbuf.extend(struct.pack(">IB", vb, len(vnb)) + vnb)
    if len(bom_body) % 16:
        bom_body.extend(b"\x00" * (16 - (len(bom_body) % 16)))
    vars_off, vars_len = len(bom_body), len(vbuf)
    bom_body.extend(vbuf)

    if len(bom_body) % 16:
        bom_body.extend(b"\x00" * (16 - (len(bom_body) % 16)))
    idx_off = len(bom_body)
    ibuf = bytearray(struct.pack(">I", len(ptrs)))
    for addr, ln in ptrs:
        ibuf.extend(struct.pack(">II", addr, ln))
    ibuf.extend(struct.pack(">I", 0))
    idx_len = len(ibuf)
    bom_body.extend(ibuf)

    struct.pack_into(">8sIIIIII", bom_body, 0, verifier.BOM_MAGIC, 1, len(ptrs) - 1, idx_off, idx_len, vars_off, vars_len)
    return cpio_raw, bytes(bom_body), len(nodes), max(1, (total_512 + 1) // 2)


def pack_xar_pkg(out_pkg: Path, app_dir: Path, version: str, bundle_id: str = "com.music.bitchord") -> None:
    clean_ver = version.strip().lstrip("v")
    with tempfile.TemporaryDirectory(prefix="bitchord-pkg-") as tmp:
        stage = Path(tmp) / "root"
        stage.mkdir()
        shutil.copytree(app_dir, stage / "BitChord.app", symlinks=True)
        cpio_raw, bom_bytes, num_files, install_kb = build_odc_cpio_and_bom(stage)
        payload_gz = gzip_compress_stripped(cpio_raw)

        pkginfo_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f'<pkg-info overwrite-permissions="true" relocatable="false" identifier="{bundle_id}" '
            f'postinstall-action="none" version="{clean_ver}" format-version="2" '
            f'generator-version="BitChordPackager-2.0" install-location="/Applications" auth="root">\n'
            f'    <payload numberOfFiles="{num_files}" installKBytes="{install_kb}"/>\n'
            f'    <bundle id="{bundle_id}" CFBundleIdentifier="{bundle_id}" path="./BitChord.app" '
            f'CFBundleVersion="{clean_ver}" CFBundleShortVersionString="{clean_ver}"/>\n'
            f'    <bundle-version><bundle id="{bundle_id}"/></bundle-version>\n'
            "</pkg-info>\n"
        ).encode("utf-8")

        comp_name = "BitChord-component.pkg"
        dist_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<installer-gui-script minSpecVersion="2">\n'
            f"    <title>BitChord {clean_ver}</title>\n"
            '    <options customize="never" require-scripts="false" hostArchitectures="arm64,x86_64"/>\n'
            '    <domains enable_anywhere="false" enable_currentUserHome="false" enable_localSystem="true"/>\n'
            f'    <choices-outline><line choice="default"><line choice="{bundle_id}"/></line></choices-outline>\n'
            '    <choice id="default"/>\n'
            f'    <choice id="{bundle_id}" visible="false"><pkg-ref id="{bundle_id}"/></choice>\n'
            f'    <pkg-ref id="{bundle_id}" version="{clean_ver}" onConclusion="none" installKBytes="{install_kb}">#{comp_name}</pkg-ref>\n'
            "</installer-gui-script>\n"
        ).encode("utf-8")

        heap = bytearray(b"\x00" * 20)

        def put_heap(raw: bytes, use_zlib: bool) -> Dict[str, Any]:
            ext_sha = hashlib.sha1(raw).hexdigest()
            arch = zlib_compress_78da(raw) if use_zlib else raw
            enc = "application/x-gzip" if use_zlib else "application/octet-stream"
            arch_sha = hashlib.sha1(arch).hexdigest()
            off = len(heap)
            heap.extend(arch)
            return {"offset": off, "length": len(arch), "size": len(raw), "enc": enc, "asha": arch_sha, "esha": ext_sha}

        m_dist = put_heap(dist_xml, True)
        m_bom = put_heap(bom_bytes, True)
        m_info = put_heap(pkginfo_xml, True)
        m_pay = put_heap(payload_gz, False)

        def fxml(fid: int, nm: str, m: Dict[str, Any], ind: str) -> str:
            return (
                f'{ind}<file id="{fid}">\n'
                f"{ind}    <data>\n"
                f'{ind}        <length>{m["length"]}</length>\n'
                f'{ind}        <offset>{m["offset"]}</offset>\n'
                f'{ind}        <size>{m["size"]}</size>\n'
                f'{ind}        <encoding style="{m["enc"]}"/>\n'
                f'{ind}        <extracted-checksum style="sha1">{m["esha"]}</extracted-checksum>\n'
                f'{ind}        <archived-checksum style="sha1">{m["asha"]}</archived-checksum>\n'
                f"{ind}    </data>\n"
                f"{ind}    <type>file</type>\n"
                f"{ind}    <name>{nm}</name>\n"
                f"{ind}</file>\n"
            )

        toc_raw = (
            '<?xml version="1.0" encoding="utf-8"?>\n<xar>\n    <toc>\n'
            "        <creation-time>2026-01-01T00:00:00</creation-time>\n"
            '        <checksum style="sha1"><offset>0</offset><size>20</size></checksum>\n'
            + fxml(1, "Distribution", m_dist, "        ")
            + f'        <file id="2">\n            <type>directory</type>\n            <name>{comp_name}</name>\n'
            + fxml(3, "Bom", m_bom, "            ")
            + fxml(4, "PackageInfo", m_info, "            ")
            + fxml(5, "Payload", m_pay, "            ")
            + "        </file>\n    </toc>\n</xar>\n"
        ).encode("utf-8")
        toc_comp = zlib_compress_78da(toc_raw)
        heap[0:20] = hashlib.sha1(toc_comp).digest()

        out_pkg.parent.mkdir(parents=True, exist_ok=True)
        with open(out_pkg, "wb") as f:
            f.write(struct.pack(">4sHHQQI", verifier.XAR_MAGIC, 28, 1, len(toc_comp), len(toc_raw), 1))
            f.write(toc_comp)
            f.write(heap)


def normalize_native_pkg_xar(raw_pkg: Path, out_pkg: Path) -> None:
    """
    Re-packs a pkgbuild/productbuild .pkg so XAR streams use raw zlib 0x78 0xda
    and Payload uses a stripped gzip header (1f 8b 08 00 00 00 00 00).
    """
    pkg_b = raw_pkg.read_bytes()
    _, hdr_sz, _, toc_clen, _, _ = struct.unpack(">4sHHQQI", pkg_b[:28])
    toc_xml = zlib.decompress(pkg_b[hdr_sz : hdr_sz + toc_clen])
    heap_in = hdr_sz + toc_clen
    root = ET.fromstring(toc_xml.decode("utf-8"))
    toc_el = root.find("toc")
    assert toc_el is not None

    # Drop any pre-normalization signature element before re-signing with productsign
    for sig_tag in ("signature", "x-signature"):
        old_sig = toc_el.find(sig_tag)
        if old_sig is not None:
            toc_el.remove(old_sig)

    heap_out = bytearray(b"\x00" * 20)
    cksum_el = toc_el.find("checksum")
    if cksum_el is not None:
        off_el = cksum_el.find("offset")
        sz_el = cksum_el.find("size")
        if off_el is not None:
            off_el.text = "0"
        if sz_el is not None:
            sz_el.text = "20"

    for f_el in toc_el.iter("file"):
        fname = (f_el.findtext("name") or "").strip()
        d_el = f_el.find("data")
        if d_el is None:
            continue
        doff = int(d_el.findtext("offset", "0"))
        dlen = int(d_el.findtext("length", "0"))
        enc_el = d_el.find("encoding")
        enc_style = enc_el.attrib.get("style", "application/octet-stream") if enc_el is not None else "application/octet-stream"
        archived_in = pkg_b[heap_in + doff : heap_in + doff + dlen]
        extracted = zlib.decompress(archived_in) if enc_style in ("application/x-gzip", "application/zlib") else archived_in

        if fname == "Payload":
            cpio_raw = gzip.decompress(extracted) if extracted[:2] == b"\x1f\x8b" else extracted
            extracted = gzip_compress_stripped(cpio_raw)
            archived_out = extracted
            out_style = "application/octet-stream"
        else:
            archived_out = zlib_compress_78da(extracted)
            out_style = "application/x-gzip"

        new_off = len(heap_out)
        heap_out.extend(archived_out)
        d_el.find("offset").text = str(new_off)  # type: ignore[union-attr]
        d_el.find("length").text = str(len(archived_out))  # type: ignore[union-attr]
        d_el.find("size").text = str(len(extracted))  # type: ignore[union-attr]
        if enc_el is not None:
            enc_el.attrib["style"] = out_style
        arch_chk = d_el.find("archived-checksum")
        if arch_chk is not None:
            arch_chk.attrib["style"] = "sha1"
            arch_chk.text = hashlib.sha1(archived_out).hexdigest()
        ext_chk = d_el.find("extracted-checksum")
        if ext_chk is not None:
            ext_chk.attrib["style"] = "sha1"
            ext_chk.text = hashlib.sha1(extracted).hexdigest()

    new_toc_raw = b'<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(root, encoding="utf-8")
    new_toc_comp = zlib_compress_78da(new_toc_raw)
    heap_out[0:20] = hashlib.sha1(new_toc_comp).digest()
    out_pkg.parent.mkdir(parents=True, exist_ok=True)
    with open(out_pkg, "wb") as f:
        f.write(struct.pack(">4sHHQQI", verifier.XAR_MAGIC, 28, 1, len(new_toc_comp), len(new_toc_raw), 1))
        f.write(new_toc_comp)
        f.write(heap_out)


# ---------------------------------------------------------------------------
# Native macOS execution
# ---------------------------------------------------------------------------


class EphemeralKeychain:
    def __init__(self) -> None:
        self.keychain_path: Optional[Path] = None
        self._tmp_dir: Optional[tempfile.TemporaryDirectory[str]] = None

    def setup_from_env(self) -> None:
        p12_b64 = os.environ.get("MACOS_CERT_P12_BASE64", "").strip()
        p12_pw = os.environ.get("MACOS_CERT_PASSWORD", "")
        if not p12_b64:
            return
        self._tmp_dir = tempfile.TemporaryDirectory(prefix="bitchord-kc-")
        td = Path(self._tmp_dir.name)
        p12_file = td / "cert.p12"
        p12_file.write_bytes(base64.b64decode(p12_b64))
        kc_pw = base64.b64encode(os.urandom(18)).decode("ascii")
        kc_path = td / "bitchord-signing.keychain-db"

        subprocess.run(["security", "create-keychain", "-p", kc_pw, str(kc_path)], check=True)
        subprocess.run(["security", "set-keychain-settings", "-lut", "21600", str(kc_path)], check=True)
        subprocess.run(["security", "unlock-keychain", "-p", kc_pw, str(kc_path)], check=True)
        subprocess.run(
            ["security", "import", str(p12_file), "-P", p12_pw, "-A", "-t", "cert", "-f", "pkcs12", "-k", str(kc_path)],
            check=True,
        )
        subprocess.run(
            ["security", "set-key-partition-list", "-S", "apple-tool:,apple:,codesign:", "-s", "-k", kc_pw, str(kc_path)],
            check=True,
        )
        existing_kcs = [
            ln.strip().strip('"')
            for ln in subprocess.check_output(["security", "list-keychains", "-d", "user"], text=True).splitlines()
            if ln.strip()
        ]
        subprocess.run(["security", "list-keychains", "-d", "user", "-s", str(kc_path), *existing_kcs], check=True)
        self.keychain_path = kc_path

    def close(self) -> None:
        if self.keychain_path is not None:
            subprocess.run(["security", "delete-keychain", str(self.keychain_path)], check=False)
        if self._tmp_dir is not None:
            self._tmp_dir.cleanup()


def sign_app_native_macos(app_dir: Path, sign_identity: str) -> bool:
    for ds in app_dir.rglob(".DS_Store"):
        ds.unlink(missing_ok=True)
    if sign_identity and sign_identity != "-":
        print(f"Signing {app_dir.name} with Developer ID identity: {sign_identity}")
        runtime_dir = app_dir / "Contents" / "runtime"
        if runtime_dir.is_dir():
            for p in sorted(runtime_dir.rglob("*"), reverse=True):
                if not p.is_symlink() and p.is_file() and (p.suffix in (".dylib", ".jnilib") or os.access(p, os.X_OK)):
                    if p.read_bytes()[:4] in (b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf", b"\xca\xfe\xba\xbe"):
                        subprocess.run(
                            ["codesign", "--force", "--options", "runtime", "--timestamp", "--sign", sign_identity, str(p)],
                            check=True,
                        )
        subprocess.run(
            ["codesign", "--force", "--deep", "--options", "runtime", "--timestamp", "--sign", sign_identity, str(app_dir)],
            check=True,
        )
        return True

    print(
        f"Notice: MACOS_SIGN_IDENTITY not configured — applying clean ad-hoc ('-') signature to {app_dir.name} "
        "(unsigned for Gatekeeper)."
    )
    subprocess.run(["codesign", "--force", "--deep", "--sign", "-", str(app_dir)], check=True)
    return False


def run_native_macos_packaging(args: argparse.Namespace) -> List[Path]:
    version = args.version.strip().lstrip("v")
    arch = args.arch or ("arm64" if platform.machine().lower() in ("arm64", "aarch64") else "x64")
    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    kc = EphemeralKeychain()
    try:
        kc.setup_from_env()
        if args.app_bundle:
            built_app = Path(args.app_bundle)
            if not built_app.is_dir():
                raise PackagingError(f"--app-bundle not found: {built_app}")
        else:
            gradlew = REPO_ROOT / "gradlew"
            os.chmod(gradlew, 0o755)
            cmd: List[str] = []
            if platform.machine().lower() in ("arm64", "aarch64") and arch == "x64":
                cmd.extend(["arch", "-x86_64"])
            cmd.extend([str(gradlew), ":desktopApp:createDistributable", f"-Pbitchord.version={version}", f"-Pbitchord.arch={arch}"])
            subprocess.run(cmd, cwd=REPO_ROOT, check=True)
            built_app = REPO_ROOT / "desktopApp/build/compose/binaries/main/app/BitChord.app"
            if not built_app.is_dir():
                alt = REPO_ROOT / "desktopApp/build/compose/binaries/main-release/app/BitChord.app"
                built_app = alt if alt.is_dir() else built_app
            if not built_app.is_dir():
                raise PackagingError(f"Expected BitChord.app not found at {built_app}")

        with tempfile.TemporaryDirectory(prefix="bitchord-macos-native-") as tmp:
            tmp_dir = Path(tmp)
            staged_app = tmp_dir / "BitChord.app"
            shutil.copytree(built_app, staged_app, symlinks=True)

            update_app_cfg_and_plist(
                staged_app,
                version=version,
                bundle_id=args.bundle_id,
                listen_together_server=args.listen_together_server,
                collab_server=args.collab_server,
                collab_google_client_id=args.collab_google_client_id,
                ota_url=args.ota_url,
            )

            sign_id = (args.sign_identity or os.environ.get("MACOS_SIGN_IDENTITY", "")).strip()
            inst_id = (args.installer_sign_identity or os.environ.get("MACOS_INSTALLER_SIGN_IDENTITY", "")).strip()
            dev_id_signed = sign_app_native_macos(staged_app, sign_id)

            out_zip = out_dir / f"BitChord-{version}-macos-{arch}.zip"
            out_dmg = out_dir / f"BitChord-{version}-macos-{arch}.dmg"
            out_pkg = out_dir / f"BitChord-{version}-macos-{arch}.pkg"

            out_zip.unlink(missing_ok=True)
            subprocess.run(["ditto", "-c", "-k", "--keepParent", str(staged_app), str(out_zip)], check=True)

            dmg_stage = tmp_dir / "dmg-stage"
            dmg_stage.mkdir()
            shutil.copytree(staged_app, dmg_stage / "BitChord.app", symlinks=True)
            os.symlink("/Applications", dmg_stage / "Applications")
            out_dmg.unlink(missing_ok=True)
            subprocess.run(
                [
                    "hdiutil", "create", "-volname", "BitChord",
                    "-srcfolder", str(dmg_stage), "-ov", "-format", "UDZO",
                    "-imagekey", "zlib-level=9", str(out_dmg),
                ],
                check=True,
            )
            raw_dmg = bytearray(out_dmg.read_bytes())
            koff = len(raw_dmg) - 512
            df_off, df_len = struct.unpack(">QQ", raw_dmg[koff + 24 : koff + 40])
            df_crc = zlib.crc32(raw_dmg[df_off : df_off + df_len]) & 0xFFFFFFFF
            struct.pack_into(">III", raw_dmg, koff + 80, verifier.UDIF_CHECKSUM_CRC32, 32, df_crc)
            out_dmg.write_bytes(bytes(raw_dmg))
            if dev_id_signed:
                subprocess.run(["codesign", "--force", "--timestamp", "--sign", sign_id, str(out_dmg)], check=True)

            # Build .pkg via pkgbuild + productbuild installing cleanly to /Applications/BitChord.app
            pkg_root = tmp_dir / "pkg-root"
            pkg_root.mkdir()
            shutil.copytree(staged_app, pkg_root / "BitChord.app", symlinks=True)
            comp_pkg = tmp_dir / "BitChord-component.pkg"
            raw_prod_pkg = tmp_dir / "BitChord-product-raw.pkg"
            norm_pkg = tmp_dir / "BitChord-product-norm.pkg"

            pkgbuild_cmd = [
                "pkgbuild",
                "--root", str(pkg_root),
                "--identifier", args.bundle_id,
                "--version", version,
                "--install-location", "/Applications",
                "--compression", "legacy",
                str(comp_pkg),
            ]
            if subprocess.run(pkgbuild_cmd, capture_output=True).returncode != 0:
                # Fallback if older pkgbuild lacks --compression legacy
                subprocess.run(
                    [
                        "pkgbuild", "--root", str(pkg_root), "--identifier", args.bundle_id,
                        "--version", version, "--install-location", "/Applications", str(comp_pkg),
                    ],
                    check=True,
                )
            subprocess.run(["productbuild", "--package", str(comp_pkg), str(raw_prod_pkg)], check=True)
            normalize_native_pkg_xar(raw_prod_pkg, norm_pkg)

            out_pkg.unlink(missing_ok=True)
            if inst_id:
                subprocess.run(
                    ["productsign", "--sign", inst_id, "--timestamp", str(norm_pkg), str(out_pkg)],
                    check=True,
                )
            else:
                shutil.copy2(norm_pkg, out_pkg)

            apple_id = os.environ.get("APPLE_ID", "").strip()
            team_id = os.environ.get("APPLE_TEAM_ID", "").strip()
            app_pw = os.environ.get("APPLE_APP_SPECIFIC_PASSWORD", "").strip()
            produced = [out_zip, out_dmg, out_pkg]
            if apple_id and team_id and app_pw and dev_id_signed:
                for art in produced:
                    subprocess.run(
                        [
                            "xcrun", "notarytool", "submit", str(art),
                            "--apple-id", apple_id, "--team-id", team_id, "--password", app_pw, "--wait",
                        ],
                        check=True,
                    )
                    if art.suffix.lower() in (".dmg", ".pkg"):
                        subprocess.run(["xcrun", "stapler", "staple", str(art)], check=True)
            return produced
    finally:
        kc.close()


# ---------------------------------------------------------------------------
# Cross-repack (--base-dir) execution
# ---------------------------------------------------------------------------


def _locate_base_for_arch(base_dir: Path, arch: str) -> Tuple[Optional[Path], Optional[Path], Optional[Path], Optional[Path]]:
    app_b: Optional[Path] = None
    b_zip: Optional[Path] = None
    b_dmg: Optional[Path] = None
    b_pkg: Optional[Path] = None

    if (base_dir / f"BitChord-{arch}.app").is_dir():
        app_b = base_dir / f"BitChord-{arch}.app"
    elif (base_dir / "BitChord.app").is_dir():
        app_b = base_dir / "BitChord.app"
    elif base_dir.suffix == ".app" and base_dir.is_dir():
        app_b = base_dir

    if base_dir.is_dir():
        for child in sorted(base_dir.iterdir()):
            inferred = verifier.infer_arch_from_name(child)
            if inferred is not None and inferred != arch:
                continue
            sfx = child.suffix.lower()
            if sfx == ".zip" and b_zip is None:
                b_zip = child
            elif sfx == ".dmg" and b_dmg is None:
                b_dmg = child
            elif sfx == ".pkg" and b_pkg is None:
                b_pkg = child
    return app_b, b_zip, b_dmg, b_pkg


def run_cross_repack(args: argparse.Namespace) -> List[Path]:
    base_dir = Path(args.base_dir)
    if not base_dir.exists():
        raise PackagingError(f"--base-dir does not exist: {base_dir}")

    version = args.version.strip().lstrip("v")
    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    arches = [args.arch] if args.arch else [
        c for c in ("arm64", "x64") if any(x is not None for x in _locate_base_for_arch(base_dir, c))
    ]
    if not arches:
        raise PackagingError(f"No macOS base artifacts (.zip/.dmg/.pkg/.app) found in {base_dir}")

    jars_dir = Path(args.jars_dir) if args.jars_dir else None
    explicit_jars = [Path(j) for j in args.replace_jar]
    produced: List[Path] = []

    for arch in arches:
        app_b, base_zip, base_dmg, base_pkg = _locate_base_for_arch(base_dir, arch)
        if base_dmg is None:
            raise PackagingError(f"Cross-repack requires a base .dmg for arch={arch} in {base_dir}")

        with tempfile.TemporaryDirectory(prefix=f"bitchord-repack-{arch}-") as tmp:
            tmp_dir = Path(tmp)
            ext_dir = tmp_dir / "extracted"
            ext_dir.mkdir()

            if app_b is not None:
                app_dir = ext_dir / "BitChord.app"
                shutil.copytree(app_b, app_dir, symlinks=True)
            elif base_zip is not None:
                verifier.extract_zip_preserving_symlinks(base_zip, ext_dir)
                app_dir = next(p for p in sorted(ext_dir.glob("*.app")) if p.is_dir())
            else:
                # Extract BitChord.app directly from base_dmg's HFS+ partition
                dmg_b = base_dmg.read_bytes()
                xml_off, xml_len = struct.unpack(">QQ", dmg_b[-512 + 216 : -512 + 232])
                pl = plistlib.loads(dmg_b[xml_off : xml_off + xml_len])
                for entry in pl["resource-fork"]["blkx"]:
                    nm = str(entry.get("Name") or entry.get("CFName") or "")
                    if "Apple_HFS" in nm or len(pl["resource-fork"]["blkx"]) == 1:
                        mish = entry["Data"]
                        m_sec_cnt, m_data_off = struct.unpack(">QQ", mish[16:32])
                        n_runs = struct.unpack(">I", mish[200:204])[0]
                        pbuf = bytearray(m_sec_cnt * 512)
                        for r in range(n_runs):
                            roff = 204 + r * 40
                            rt, _, rs, rc, rco, rcl = struct.unpack(">IIQQQQ", mish[roff : roff + 40])
                            if rt == verifier.BLKX_RUN_TERM:
                                break
                            if rt in (verifier.BLKX_RUN_ZERO, verifier.BLKX_RUN_IGNORE, verifier.BLKX_RUN_COMMENT) or rcl == 0:
                                continue
                            cs = dmg_b[m_data_off + rco : m_data_off + rco + rcl]
                            pbuf[rs * 512 : (rs + rc) * 512] = zlib.decompress(cs) if rt == verifier.BLKX_RUN_ZLIB else cs
                        verifier.extract_hfsplus_tree(bytes(pbuf), ext_dir)
                        break
                app_dir = next(p for p in sorted(ext_dir.glob("*.app")) if p.is_dir())

            renamed_jars = sync_app_jars(app_dir, jars_dir, explicit_jars) if (jars_dir or explicit_jars) else {}
            update_app_cfg_and_plist(
                app_dir,
                version=version,
                bundle_id=args.bundle_id,
                listen_together_server=args.listen_together_server,
                collab_server=args.collab_server,
                collab_google_client_id=args.collab_google_client_id,
                ota_url=args.ota_url,
            )

            code_res_bytes = rebuild_code_resources(app_dir)
            info_bytes = (app_dir / "Contents" / "Info.plist").read_bytes()
            exec_name = str(plistlib.loads(info_bytes).get("CFBundleExecutable", "BitChord")).strip() or "BitChord"
            patch_macho_code_signature_slots(
                app_dir / "Contents" / "MacOS" / exec_name,
                info_plist_bytes=info_bytes,
                code_resources_bytes=code_res_bytes,
            )

            out_zip = out_dir / f"BitChord-{version}-macos-{arch}.zip"
            out_dmg = out_dir / f"BitChord-{version}-macos-{arch}.dmg"
            out_pkg = out_dir / f"BitChord-{version}-macos-{arch}.pkg"

            print(f"[{arch}] Writing deterministic ZIP -> {out_zip.name}")
            write_deterministic_zip(app_dir, out_zip)

            print(f"[{arch}] Writing XAR PKG (0x78 0xda + POSIX cksum Bom + stripped gzip Payload) -> {out_pkg.name}")
            pack_xar_pkg(out_pkg, app_dir, version=version, bundle_id=args.bundle_id)

            # jpackage DMGs include Contents/app/.package ('BitChord'); seal it in CodeResources & Slot -3 before repacking DMG
            pkg_marker = app_dir / "Contents" / "app" / ".package"
            if not pkg_marker.exists():
                pkg_marker.write_bytes(b"BitChord")
                dmg_code_res = rebuild_code_resources(app_dir)
                patch_macho_code_signature_slots(
                    app_dir / "Contents" / "MacOS" / exec_name,
                    info_plist_bytes=info_bytes,
                    code_resources_bytes=dmg_code_res,
                )

            print(f"[{arch}] Updating HFS+ partition in-place & repacking UDZO DMG -> {out_dmg.name}")
            repack_dmg_inplace(base_dmg, app_dir, renamed_jars, out_dmg)
            produced.extend([out_zip, out_dmg, out_pkg])

    return produced


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Package & verify reproducible BitChord macOS .zip, .dmg, and .pkg artifacts.")
    parser.add_argument("--version", required=True, help="Release version string (e.g. 1.9.2)")
    parser.add_argument("--arch", choices=["arm64", "x64"], default=None, help="Target macOS architecture")
    parser.add_argument("--output-dir", default="dist", help="Destination directory for built artifacts")
    parser.add_argument("--bundle-id", default="com.music.bitchord", help="CFBundleIdentifier")
    parser.add_argument("--app-bundle", default=None, help="(macOS native) Existing BitChord.app bundle")
    parser.add_argument("--base-dir", default=None, help="(Cross-repack mode) Directory containing base macOS artifacts")
    parser.add_argument("--jars-dir", default=None, help="(Cross-repack mode) Directory of updated JVM JARs")
    parser.add_argument("--replace-jar", action="append", default=[], help="(Cross-repack mode) Specific JAR replacement")
    parser.add_argument("--sign-identity", default="", help="Developer ID Application identity")
    parser.add_argument("--installer-sign-identity", default="", help="Developer ID Installer identity")
    parser.add_argument("--listen-together-server", default=os.environ.get("LISTEN_TOGETHER_SERVER", ""))
    parser.add_argument("--collab-server", default=os.environ.get("COLLAB_SERVER", ""))
    parser.add_argument("--collab-google-client-id", default=os.environ.get("COLLAB_GOOGLE_CLIENT_ID", ""))
    parser.add_argument("--ota-url", default=os.environ.get("OTA_JSON_URL", ""))
    args = parser.parse_args(argv)

    try:
        if args.base_dir:
            produced = run_cross_repack(args)
        elif sys.platform == "darwin":
            produced = run_native_macos_packaging(args)
        else:
            raise PackagingError(
                f"Host platform is '{sys.platform}'; pass --base-dir <dir> for Linux cross-repack mode, or run on macOS."
            )

        verify_cmd = [
            sys.executable,
            str(SCRIPT_DIR / "verify-macos-artifacts.py"),
            "--expected-version",
            args.version.strip().lstrip("v"),
            "--bundle-id",
            args.bundle_id,
            *[str(p) for p in produced],
        ]
        if args.arch:
            verify_cmd.extend(["--expected-arch", args.arch])
        subprocess.run(verify_cmd, check=True)
        return 0
    except (PackagingError, subprocess.CalledProcessError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
