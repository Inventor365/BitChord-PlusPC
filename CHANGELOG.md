# Changelog

All notable changes to **BitChord PlusPC** (`https://github.com/Inventor365/BitChord-PlusPC`) are documented in this file.

---

## [1.10.0] — 2026-11-15 (`versionCode = 41`)

### Added
- **First-Party Collaborative Playlists & Contributor Attribution** across **Android**, **Desktop (macOS, Windows, Linux)**, **Web**, and **Backend** ([`docs/COLLABORATIVE_PLAYLISTS.md`](docs/COLLABORATIVE_PLAYLISTS.md)):
  - Create shared playlists, invite friends as **Editors** or **Viewers**, add/remove/reorder songs together, and see contributor avatars + `"Added by you"` / `"Added by <Name>"` attribution on every track (inspired by Apple Music Collaborative Playlists & Spotify Blend).
  - **Existing Personal Playlist Conversion ("Make Collaborative")**: Convert any existing YouTube Music, Local/Device, Desktop, or imported Spotify playlist into a collaborative playlist in place without deleting or duplicating your original personal playlist; existing tracks are attributed to you as the owner.
  - **Reversible Sharing ("Stop Sharing" / "Make Private")**: Revoke all active invite links and remove collaborators at any time while retaining your full playlist and historical contributor attribution privately.
  - **Granular Owner & Editor Controls**: Configure whether editors may remove any track or only tracks they added (`editorCanRemove: "any" | "own"`), toggle track reordering (`editorCanReorder`) and invite generation (`editorCanInvite`), switch member roles, remove members, or transfer ownership.
  - **Activity Feed & Contributor Filter**: Lightweight playlist activity log (`"Saksham added Midnight City"`, etc.) and one-tap contributor filter chips on playlist detail pages.
  - **First-Party Playlist Management Organization**: Dedicated **Collaborative Playlists** and **Shared with Me** shelves alongside personal playlists on Android, Desktop, and Web, plus direct `"Add to Playlist"` picker integration and `bitchord://collab/invite/...` / `/collab/invite/bci_...` deep links.
  - **Portable Playlist Exchange (`PlaylistExchange` / `CsvPlaylistParser`)**: Import and export portable playlists across `.csv`, `.m3u`/`.m3u8`, and `.json` formats.

### Security
- **Server-Verified Google OpenID Connect (OIDC) Authentication (`backend/auth/oidc.go`, `PkceGoogleAuth`)**:
  - Verifies RS256 Google ID tokens against Google's rotating JWKS (`https://www.googleapis.com/oauth2/v3/certs`) and supports **OAuth 2.0 Authorization Code + PKCE (S256)** across Android, Desktop loopback receivers, and Web popups/GSI.
  - Never requests or stores Google passwords; rejects self-declared usernames/emails and ignores client-supplied contributor fields.
  - Issues 256-bit `bcs_<64-hex>` session tokens with `SHA-256` server-side storage, configurable 30-day TTL, instant logout revocation, and constant-time `bci_<base64url>` invite token validation.
  - Guest Mode remains completely unchanged for browsing, searching, streaming, local playback, and Listen Together.

### Improved
- **Player & Lyrics Rendering**:
  - Restored live lyric clock continuity across pause/resume cycles and prevented rewind jumps during main-thread frame stalls.
  - Removed hard box clipping around word-sweep glow in `PlayerLyrics` across `sharedUi` and `desktopUi`.
  - Smoothly crossfaded Apple Music & Spotify animated canvases into the static cover during swipe gestures and prevented static sleeve bleed behind full-bleed album motion art.
- **Audio Quality & Metadata**:
  - Prioritized authenticated YouTube Music Premium `256 kbps` streams when High/Lossless quality is selected (`PremiumAudioPolicy`).
  - Added expandable Song Credits (performers, writers, producers) inside `SongInfoDialog` and track play counts on Album and Artist Top Songs lists.
  - Added inline search across Library shelves across all 19 locales.

### Fixed
- **Android Upgrade Compatibility ("Invalid Signature" Fix)**: Eliminated the ephemeral `keytool -genkeypair` fallback in `.github/workflows/release.yml` and the debug-signing fallback in `tv/build.gradle.kts` that previously caused `INSTALL_FAILED_UPDATE_INCOMPATIBLE` across `v1.8.1`/`v1.9`/`v1.9.2`; provisioned and locked a single permanent RSA-4096 release certificate (`SHA-256: d6d9c697...`) across Phone, Android TV, Wear OS, and GitHub Actions Secrets.
- **macOS DMG `"Invalid argument"` (`EINVAL`) & PKG Integrity**: Enforced `<= 2,048`-sector zlib chunking inside UDIF `blkx` descriptors so `DecompressionBufferRequested` is never exceeded, recomputed `mish`, Master, and Data Fork CRC-32s, verified HFS+ catalogs with `fsck.hfsplus -fn`, and validated XAR raw-`zlib` (`0x78 0xda`) streams, `Bom` POSIX `cksum`s, and Mach-O `LC_CODE_SIGNATURE` / `CodeResources` SHA-256 digests.
- **Playback, Queue & Scrobbling**:
  - Retained played user-queued tracks in history when `Repeat All` is off so **Previous** returns to them (`QueueCoordinator`).
  - Prevented back-to-back boundary track repeats across shuffle cycles (`QueueWindow`).
  - Reported `QueueShuffle` state and unshuffled `ShuffleOrder` from `SessionPlayer` so Android Auto, car head units, and Bluetooth controllers display and toggle shuffle accurately.
  - Counted Cast and Wear OS remote-speaker playback toward local Listening Stats (`PlaybackService`).
  - Restored track number, `sourceHiRes`, and `feedbackToken` across `BoundedContextPersistence` restarts, fixed non-English locale digit formatting in `ListeningStats.dayKey`, and unified `M:SS` / `H:MM:SS` duration parsing in `ScrobbleManager`.
  - Added HTTP `Range` (`206 Partial Content`) resumable downloads with exponential-backoff retry to `AppUpdateChecker`.
  - Added automatic `hqdefault.jpg` fallback when YouTube returns HTTP 404 on upgraded `maxresdefault`/`sddefault` video thumbnails across Android (`YouTubeThumbnailFallback`) and Desktop (`DesktopArtworkCache`).
  - Preserved synced network lyrics lookup when a local MP3 carries untimed `USLT` lyrics, dropped header-only LRC metadata lines, and supported space-separated compressed LRC timestamps.

### Upstream Integrations
Integrated and adapted verified contributions from [`kushagrasinghx/BitChord`](https://github.com/kushagrasinghx/BitChord) with full author attribution:
- **`da93c6f3` (`#1554` follow-up)** — [@kushagrasinghx](https://github.com/kushagrasinghx) & [@uveshm003](https://github.com/uveshm003): Report and toggle app shuffle on external MediaSession / Android Auto controllers.
- **`7f15a6fe` (`#1378` follow-up)** — [@kushagrasinghx](https://github.com/kushagrasinghx) & [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Fold CJK romanized title asides in `TrackMatcher` (Kotlin & Web).
- **`6a05577c` / `#1360`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary) & [@kushagrasinghx](https://github.com/kushagrasinghx): Read MP3 ID3v2 `USLT` lyrics, upgrade untimed embedded lyrics when synced lines exist, and parse space-separated compressed LRC stamps.
- **`a2cd4985`** — [@kushagrasinghx](https://github.com/kushagrasinghx): Fix Hide Lyrics / Synced Lyrics migration, keep Search filter switches anonymous except Library, scope Sleep Timer fade reset to faded player, and reset `PlaylistPickerSheet` scroll on query change.
- **`9f02171f` & `4cb4076e`** — [@kushagrasinghx](https://github.com/kushagrasinghx): Unique `shelfCardKeys` for repeated library shelf items and `YouTubeThumbnailFallback` 404 memoization.
- **PR `#1773`** — [@Galaxyyss](https://github.com/Galaxyyss): Unclipped word-sweep glow in `PlayerLyrics` (ported to `sharedUi` & `desktopUi`).
- **PR `#1768`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Preserve played queued songs for Previous when Repeat All is off.
- **PR `#1745`** — [@aaditya079](https://github.com/aaditya079): CSV playlist parser & portable playlist import (`CsvPlaylistParser`).
- **PR `#1738`** — [@peledson11](https://github.com/peledson11) *(adapted for PlusPC)*: Prioritize `PAGES_ORIGIN` in browser extension `SITE_ORIGINS`.
- **PR `#1735`** — [@Galaxyyss](https://github.com/Galaxyyss): Avoid boundary repeat across small-queue shuffle cycles.
- **PR `#1734`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Locale-independent `Locale.US` formatting in `ListeningStats.dayKey`.
- **PR `#1732`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Shared `durationMillis()` parsing in `ScrobbleManager`.
- **PR `#1729`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Persist `trackNumber`, `sourceHiRes`, and `feedbackToken` in `BoundedContextPersistence`.
- **PR `#1724`** — [@aaditya079](https://github.com/aaditya079): Search filter inside Library screen across all 19 locales (`sharedUi` & `desktopUi`).
- **PR `#1723`** — [@aaditya079](https://github.com/aaditya079): Parse and display track play counts on Album and Artist Top Songs rows.
- **PR `#1691`** — [@Galaxyyss](https://github.com/Galaxyyss): LyricClock pause-resume handoff and monotonic extrapolation (`sharedUi` & `desktopUi`).
- **PR `#1688`** — [@datingesp-hash](https://github.com/datingesp-hash): Count Cast and Wear OS remote speaker playback in Listening Stats.
- **PR `#1683` & `#1603`** — [@Galaxyyss](https://github.com/Galaxyyss): Fade Apple Music animated cover during swipe gestures and prevent static album cover bleed (`sharedUi`, `desktopUi`, & `DetailScreen`).
- **PR `#1642`** — [@aaditya079](https://github.com/aaditya079): Parse YouTube Music song credits and display scrollable credit rows in `SongInfoDialog`.
- **PR `#1640`** — [@aaditya079](https://github.com/aaditya079): Authenticated YouTube Music Premium `256 kbps` stream selection (`PremiumAudioPolicy` on Android & Desktop).
- **PR `#1588`** — [@whymeme](https://github.com/whymeme): Resumable HTTP `Range` APK downloads in `AppUpdateChecker`.
- **PR `#1584`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Restore canonical queue order around current song when disabling shuffle on Web.
- **PR `#1534`** — [@arpankanwer](https://github.com/arpankanwer): Round Listen Together round-trip latency on Web.
- **PR `#1517`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Auto-focus search input on Web Search page.
- **PR `#1455` & `#1454`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Web `aria-keyshortcuts` tooltips and MediaSession album/artwork/`loopMix` playhead integration.
- **PR `#1453`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Persist lyrics translation and romanization preferences on Web.
- **PR `#1445`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Apple Music–style marquee for overflowing LCD track titles on Web.
- **PR `#1440`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): `PageError` route boundary and automatic one-time reload on stale Web chunks.
- **PR `#1427`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Include `non_music_track` music videos in Web playlists.
- **PR `#1333`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Retain same-name Google accounts in Web extension account discovery.
- **PR `#1243`** — [@ArekatlaNishanthchowdary](https://github.com/ArekatlaNishanthchowdary): Step volume up from silence when wheeling while muted on Web.

### Platform and Packaging
- Added `scripts/verify-android-upgrade.sh`, `scripts/macos/package-macos.py`, `scripts/macos/verify-macos-artifacts.py`, and `.github/workflows/ci.yml` cross-platform verification gates.
- Built and verified all 21 release binaries (`Phone x4`, `TV x4`, `Wear OS x1`, `macOS arm64/x64 .dmg/.pkg/.zip x6`, `Windows x64 .exe/.msi/.zip x3`, `Linux x86_64 .AppImage/.deb/.rpm x3`) + `SHA256SUMS.txt`.

### Documentation
- Overhauled [`README.md`](README.md) and [`CONTRIBUTING.md`](CONTRIBUTING.md), and added [`CHANGELOG.md`](CHANGELOG.md), [`docs/COLLABORATIVE_PLAYLISTS.md`](docs/COLLABORATIVE_PLAYLISTS.md), [`docs/SIGNING.md`](docs/SIGNING.md), [`docs/MACOS_PACKAGING.md`](docs/MACOS_PACKAGING.md), and [`docs/RELEASE_PROCESS.md`](docs/RELEASE_PROCESS.md).

---

## [1.9.2] — 2026-11-15 (`versionCode = 40`)
- Merged upstream `v1.9.1` (`acf219a7`) and integrated 58 community pull requests across Android, Desktop, TV, and Wear OS.
- Restored and hardened Windows, macOS, and Linux desktop installers and migrated OTA update endpoints to `Inventor365/Bitchord-OTA` and `Inventor365/BitChord-PlusPC`.
