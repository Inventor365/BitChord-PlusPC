# macOS DMG & PKG Packaging and Verification

BitChord PlusPC distributes three macOS artifact formats for both **Apple Silicon (`arm64`)** and **Intel (`x64`)**:

- `BitChord-<version>-macos-{arm64,x64}.dmg` — UDIF compressed HFS+ drag-to-Applications disk image
- `BitChord-<version>-macos-{arm64,x64}.pkg` — Flat XAR installer package installing `/Applications/BitChord.app`
- `BitChord-<version>-macos-{arm64,x64}.zip` — Portable zipped `.app` bundle

---

## 1. Root Cause of Earlier macOS DMG `"Invalid argument"` (`EINVAL`) Failure

Investigation of earlier `.dmg` failures (`DiskImageMounter: The disk image couldn't be opened — Invalid argument`) uncovered three low-level UDIF/HFS+ defects:

1. **UDIF `DecompressionBufferRequested` Overflow (`EINVAL`)**:
   - Apple's `IOHDIXController` / `DiskImageMounter` rejects any UDIF `BLKX_RUN_ZLIB` (`0x80000005`) chunk whose sector count exceeds the `mish` header's `DecompressionBufferRequested` ceiling (`2,056` 512-byte sectors = `1,052,672` bytes) or whose compressed `zlib` payload exceeds its uncompressed byte length.
   - Compressing a large contiguous HFS+ run into a single `> 2,048`-sector `BLKX_RUN_ZLIB` chunk caused `DiskImageMounter` to fail immediately with `EINVAL`.
2. **UDIF `mish` Partition CRC-32 Offset & Master CRC-32 Endianness**:
   - In a 204-byte `mish` block descriptor, the `UDIFChecksum` sits at offset `64..76` (`type = 2`, `bitness = 32`, big-endian CRC-32 at `72..76`) and covers **only non-zero-fill runs** (`0x80000005` zlib and `0x00000001` raw, skipping `0x00000002` zero-fill).
   - The `koly` trailer's Master Checksum (`offset 352..364`) is the CRC-32 over the concatenated 4-byte big-endian `mish` CRC-32s of all 8 partitions, while the Data Fork Checksum (`offset 80..92`) covers bytes `0..xml_offset`.
3. **Flat `.pkg` XAR Encoding & `Payload` Gzip Header**:
   - Flat `.pkg` archives declare `<encoding style="application/x-gzip"/>` in the XAR TOC, which Apple's `xar` / `Installer.app` expects to be raw `zlib` (`0x78 0xda`) for metadata streams (`Bom`, `PackageInfo`, `Distribution`), whereas `Payload` is a 512-byte-padded `cpio` archive wrapped in a stripped-header gzip stream (`1f 8b 08 00 00 00 00 00`) with exact POSIX `cksum` and byte lengths recorded in `Bom`.

---

## 2. Packaging & Verification Pipeline (`scripts/macos/`)

All macOS packaging and structural validation lives in two versioned scripts:

### `scripts/macos/package-macos.py`
Supports two deterministic execution modes:
1. **Native macOS Runner (`darwin`)**:
   - Assembles `BitChord.app` via `./gradlew :desktopApp:createDistributable`, signs nested dylibs/runtime and outer bundle with `codesign --force --options runtime --timestamp` (Developer ID Application when `MACOS_SIGN_IDENTITY` is configured, or clean ad-hoc `-` for unsigned test/community builds), creates the UDZO `.dmg` with `/Applications` drop link via `hdiutil`, builds the flat `.pkg` via `pkgbuild` + `productbuild`, optionally submits to `xcrun notarytool` + `stapler` when Apple credentials are provided, and runs `verify-macos-artifacts.py`.
2. **Deterministic Cross-Host Repack (`--base-dir`)**:
   - Updates `BitChord.app` (`Info.plist`, `BitChord.cfg`, application JARs, `_CodeSignature/CodeResources` `files2` SHA-256 digests, and `MacOS/BitChord` Mach-O `LC_CODE_SIGNATURE` Special Slots `-1` and `-3`), splits modified HFS+ runs into `<= 2,048`-sector zlib chunks (falling back to raw `0x00000001` if incompressible and `0x00000002` for zero runs), recomputes `mish`, Master, and Data Fork CRC-32s, rebuilds the XAR `.pkg` with updated `Bom` POSIX `cksum`s and TOC SHA-1 digests, and runs `verify-macos-artifacts.py`.

### `scripts/macos/verify-macos-artifacts.py`
Validates every `.zip`, `.dmg`, and `.pkg` before publication:
```bash
python3 scripts/macos/verify-macos-artifacts.py build/release-1.9.2/dist --expected-version 1.9.2
```
Checks performed:
- **`.zip` / `.app`**: `Info.plist` keys & version, Mach-O CPU type (`arm64` vs `x86_64`), `BitChord.cfg` classpath JAR presence, `_CodeSignature/CodeResources` SHA-256 integrity across all bundle files, and `MacOS/BitChord` `CodeDirectory` Special Slots `-1` and `-3` (plus `codesign --verify --deep --strict` on macOS).
- **`.dmg`**: UDIF `koly` magic, XML plist structure, max `2,048`-sector zlib chunk invariant (`run_sectors <= DecompressionBufferRequested`), `mish` CRC-32, Master CRC-32, Data Fork CRC-32, and `fsck.hfsplus -fn` catalog/extents/bitmap verification (plus `hdiutil verify` on macOS).
- **`.pkg`**: XAR header & TOC zlib stream, SHA-1 `archived-checksum` and `extracted-checksum` for every XAR file entry, `PackageInfo` version, `Bom` `BOMStore` header, and `Payload` gzip+cpio stream extraction test (`bsdtar -tzf`).

---

## 3. Signing & Notarization Status Disclosure

- When Apple Developer credentials (`MACOS_SIGN_IDENTITY`, `MACOS_INSTALLER_IDENTITY`, `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_SPECIFIC_PASSWORD`) are configured as GitHub Actions secrets, `package-macos.py` produces Developer ID–signed, notarized, and stapled `.dmg` and `.pkg` installers.
- When built without a paid Apple Developer certificate, `BitChord.app` carries a structurally valid ad-hoc Mach-O `LC_CODE_SIGNATURE` (`flags=0x2` adhoc) and **is not Apple-notarized**. On first launch on macOS Sequoia/Sonoma, Gatekeeper requires right-clicking `BitChord.app` -> **Open** (or running `xattr -cr /Applications/BitChord.app`).
