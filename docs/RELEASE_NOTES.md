# BitChord PlusPC `v1.10.0` (`versionCode = 41`)

**Multiplatform Release — Android, Android Auto, Android TV, Wear OS, macOS (`arm64` & `x64`), Windows (`x64`), Linux (`x86_64`), Web & Backend**

---

## Highlights

### 1. First-Party Collaborative Playlists & Verified Google Attribution
- **Create & Collaborate Across Platforms**: Build shared playlists across **Android**, **Desktop (macOS, Windows, Linux)**, and **Web**, invite friends as **Editors** or **Viewers**, and watch track additions, removals, and reorders arrive live over WebSocket (`/ws/collab`).
- **Convert Existing Playlists ("Make Collaborative")**: Turn any existing YouTube Music, Local/Device, Desktop, or imported Spotify playlist into a collaborative playlist without deleting or duplicating your original playlist — existing songs are automatically attributed to you as the owner.
- **Spotify Blend–Style Contributor Attribution**: Every song displays its contributor's circular avatar and `"Added by you"` / `"Added by <Name>"` attribution, backed by one-tap contributor filter chips and a lightweight **Recent Activity** feed.
- **Verified Google OpenID Connect (OIDC + PKCE S256)**: Server-verified RS256 Google ID tokens and OAuth 2.0 PKCE authentication (`backend/auth/oidc.go`, `PkceGoogleAuth`) with 256-bit `bcs_` session tokens, revocable `bci_` invite links, granular editor policies (`editorCanRemove: "any" | "own"`, `editorCanReorder`, `editorCanInvite`), and reversible **Stop Sharing (Make Private)** — while **Guest Mode** stays 100% unchanged.

### 2. Deterministic Android Upgrade Signing & Reproducible macOS Packaging
- **Permanent Android Release Signing (`v1.10.0+`)**: Eliminated the ephemeral CI keystore generator that broke upgrades across `v1.8.1`/`v1.9`/`v1.9.2` and locked `:app`, `:tv`, `:wearos`, and GitHub Secrets onto one permanent RSA-4096 certificate (`SHA-256: d6d9c697c070a5ca5f4e172a13b6263fe2f3a35fff5d110596509126320c3bb2`), verified across all 9 APKs by `scripts/verify-android-upgrade.sh` *(one-time clean install required if upgrading from pre-`v1.10.0`; all future upgrades install seamlessly in place)*.
- **Verified macOS `.dmg`, `.pkg` & `.zip` Installers**: Enforced `<= 2,048`-sector UDIF `blkx` zlib chunking, verified `mish`/Master/Data Fork CRC-32s and `fsck.hfsplus -fn`, and validated flat `.pkg` XAR raw-`zlib` (`0x78 0xda`) streams, `Bom` POSIX `cksum`s, and Mach-O `LC_CODE_SIGNATURE` / `CodeResources` SHA-256 seals via `scripts/macos/verify-macos-artifacts.py`.

### 3. 30+ Newly Integrated Upstream Fixes & Community PRs
- **Playback, Audio & Queue**: Authenticated YouTube Music Premium `256 kbps` stream selection (`#1640`), Android Auto / Bluetooth external controller shuffle sync (`da93c6f3`), preserved played queued songs for Previous when Repeat All is off (`#1768`), boundary-safe small-queue shuffle cycles (`#1735`), Cast/Wear remote speaker Listening Stats (`#1688`), CJK romanized aside matching (`7f15a6fe`), and resumable HTTP `Range` OTA APK downloads (`#1588`).
- **Lyrics, UI & Library**: Monotonic karaoke `LyricClock` pause/resume continuity (`#1691`), unclipped word-sweep glow (`#1773`), MP3 `USLT` + synced upgrade fallback (`#1360` / `6a05577c`), Apple Music animated cover swipe fade & bleed fix (`#1683` / `#1603`), expandable Song Credits (`#1642`), Album/Artist track play counts (`#1723`), Library search across 19 locales (`#1724`), portable CSV/M3U/JSON playlist exchange (`#1745`), YouTube 404 thumbnail fallback (`4cb4076e`), and 12 Web player & extension PRs (`#1738`, `#1584`, `#1534`, `#1517`, `#1455`, `#1454`, `#1453`, `#1445`, `#1440`, `#1427`, `#1333`, `#1243`).

---

## Verified Release Assets (21 Packages + `SHA256SUMS.txt`)
- **Android Phone & Auto (4)**: `BitChord-v1.10.0.apk`, `BitChord-v1.10.0_arm64-v8a.apk`, `BitChord-v1.10.0_armeabi-v7a.apk`, `BitChord-v1.10.0_x86_64.apk`
- **Android TV / Fire TV (4)**: `BitChordTV-v1.10.0.apk`, `BitChordTV-v1.10.0_arm64-v8a.apk`, `BitChordTV-v1.10.0_armeabi-v7a.apk`, `BitChordTV-v1.10.0_x86_64.apk`
- **Wear OS (1)**: `BitChordWear-v1.10.0.apk`
- **macOS Apple Silicon & Intel (6)**: `BitChord-1.10.0-macos-{arm64,x64}.{dmg,pkg,zip}` *(ad-hoc signed, non-notarized — right-click → Open or `xattr -cr /Applications/BitChord.app` on first launch)*
- **Windows x64 (3)**: `BitChord-1.10.0-windows-x64-setup.exe`, `BitChord-1.10.0-windows-x64.msi`, `BitChord-1.10.0-windows-x64-portable.zip`
- **Linux x86_64 (3)**: `BitChord-1.10.0-linux-x86_64.AppImage`, `BitChord-1.10.0-linux-amd64.deb`, `BitChord-1.10.0-linux-x86_64.rpm`

Full PR attribution and technical details: [`CHANGELOG.md`](https://github.com/Inventor365/BitChord-PlusPC/blob/main/CHANGELOG.md) · [`docs/COLLABORATIVE_PLAYLISTS.md`](https://github.com/Inventor365/BitChord-PlusPC/blob/main/docs/COLLABORATIVE_PLAYLISTS.md) · [`docs/SIGNING.md`](https://github.com/Inventor365/BitChord-PlusPC/blob/main/docs/SIGNING.md) · [`docs/MACOS_PACKAGING.md`](https://github.com/Inventor365/BitChord-PlusPC/blob/main/docs/MACOS_PACKAGING.md)
