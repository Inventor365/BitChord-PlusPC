# Changelog

All notable changes to **Sonara** (`https://github.com/Inventor365/Sonara`), formerly BitChord PlusPC, are documented in this file.

---

## [Unreleased]

### Added
- **Winter theme**, an arctic scene behind the top of every page, made to look like a film still rather than a drawing. Headings use Quicksand (OFL). Each mode has its own launcher icon in the Sonara mark's shape, and "Match app icon" follows light and dark.
  - *Polar night* (dark mode): a long-exposure sky (Milky Way, thousands of faint stars), glacier peaks backlit by the aurora, mist on the ice field and two emperor penguins far out on it. A live aurora (an AGSL shader on Android 13+, crossfading frames below) shows green-to-violet curtains with drifting rays. Bright stars twinkle, and a shooting star crosses every 8–20 s. Fine soft snow falls in front.
  - *Arctic day* (light mode): a bright sky with cirrus, sunlit glaciers with blue shadows, a faint daytime aurora, and a small sun that crosses from left (sunrise) to right (sunset) with the time of day, warming the horizon morning and evening.
  - The backplates are rendered by `scripts/winter/render_backplates.py` (CC0, see `assets/licenses/WINTER-ARTWORK.txt`). The aurora glows through the nav bar and mini player from the one shared layer, not shaded twice. By day the bars are frosted ice glass.
- **Winter effects** settings (shown while Winter is the theme): Aurora animation, Snowfall, Stars and shooting stars, Liquid-glass blur and Frost edges, each taking effect live. Turning one off removes it. Low Power Mode, Data Saver, battery saver, system animations off, and Sonara being out of sight hold everything still instead of removing it. Low-RAM, under-4 GB or Android 9 phones start with the heavy effects off.
- **Night sky after sunset** (Winter, off by default): in light mode, switches to the polar night from 18:00 to 06:00.
- **Home quotes and greetings**: a one-liner under the greeting (funny, dry or kind) and several greetings for each time of day, changing every time the app is opened. In all 18 languages.
- **Android Auto**: Albums and Artists folders (under More), a "couldn't load" row instead of an empty list when offline, and a translated Lyrics tab.
- **Lyrics on the lock screen** (Settings → Lyrics, off by default): the line being sung in place of the title in the notification.

### Changed
- **Page titles match each theme**: the greeting, its quote and the Library, Settings, Explore and Search headings take the theme's own colours and glow instead of plain black or white over the artwork. Winter day is white on a deeper sky blue, Winter night icy white with an aurora-teal glow, Vice warm white with a neon-pink glow, and Doom emerald-white with a green glow. Sonara is unchanged.
- **Winter's aurora costs a fraction of what it did**: it is now shaded once at quarter resolution into a cached texture that the sky and both bars reuse. It used to be redrawn at full screen size every frame, once more for each bar. Idle Home with every effect on went from 95% to 31% janky frames on a Poco F3.
- **The app icon changes the moment a theme is picked**, for all four themes. It used to wait until the app left the screen, because swapping the launcher entry closed the running app. Every home-screen icon now opens a small entry screen that hands over to the app under its own name, so the swap no longer closes it. On a Xiaomi 11i (Android 16, Launcher3), the swap took 2 ms and the home screen updated within about 0.8 s.
- **Vice and Doom revamped.** Their launcher icons are recoloured versions of the Sonara mark, with no extra shapes. Doom is now forged gunmetal with an emerald glow: a dial with engraved ticks and brass studs behind the header, a velvet green player and rounded corners everywhere. Its medieval font is replaced by Oswald (OFL). Cinzel is removed.
- The thin coloured outline around the nav bar and the mini player in Vice and Doom is gone.
- The Home greeting and quote get a soft halo in the page's colour, so they stay readable over a theme's artwork.

### Fixed
- **Android Auto showed lyrics instead of the song**: the car's now-playing card (and every Bluetooth head unit, speaker and pair of earbuds) now always gets the real title and artist. A lyric only replaces the title on this phone's own notification, only if you turn it on, and never while a car is connected or sound is going to Bluetooth, a car or a dock.
- **Imports sat on "Reading playlist…"**: Spotify's Web API answers the anonymous token with a 429 and a Retry-After of about 7 hours, which was waited out page by page. The web player's own query is now tried first, a Retry-After over 10 s fails over at once, every request has a 20 s deadline, and reading the playlist has a 4-minute one. Reading shows live counts ("Reading playlist (300 / 882)…"), and a failure shows a Retry button.
- **Apple Music imports stopped at 300 songs**: the page's `trackCount` counts the songs on the page, not in the playlist. Paging now follows the page's own next link, and the web player's token is found whichever way its header is written.
- **Import summaries account for every song**: they now say how many Spotify doesn't name in your country (it gives no title for region-restricted tracks), and how many were the same song twice. Signed out, the summary no longer says "Couldn't create it on YouTube Music".
- **Long ("scrolling") screenshots repeated the top of Home**, in every theme: pull-to-refresh took the screenshot's scroll requests for a pull and reported them as scrolled. It now stands aside while a screenshot is taken.
- **Large playlist imports stopped at about 92 songs.**
  - Spotify playlists are read past their first 100 songs through Spotify's paged API, falling back to the web player's own query.
  - Apple Music playlists, which used to stop at the 100 the page holds, are read through Apple's catalog API, including shared `pl.u-` playlists and any storefront.
  - Every page is retried with growing pauses on rate limits and server errors.
  - Songs are added in batches that wait out a new playlist's settling period (a batch sent then used to be lost unseen). Batches YouTube refuses are split until the songs it won't take are found.
  - Matching runs five at a time with visible progress, and the whole import keeps going if you close the dialog or leave the app.
  - It ends with a summary ("Imported 781 of 812 — 31 couldn't be matched"), the unmatched songs, *Retry unmatched* and *Open playlist*. Amazon Music imports get the same matching, adding and summary.
  - Playlists made by hand from a list of songs also wait out the settling period.

---

## [2.0.0] — 2026-11-17 (`versionCode = 48`) — Sonara

### Changed
- **BitChord PlusPC is now Sonara**: new name, mark, icons and repository (`Inventor365/Sonara`). The application id, signing certificate, Windows upgrade code, Linux package name, downloads folder and `bitchord://` links are unchanged, so this installs over `v1.10.x` in place and keeps everything.
- Release files are named `Sonara-…`; the updater also accepts the older `BitChord-…` names.
- Update checks read `Inventor365/Sonara`.

### Added
- Animated welcome screen drawing the Sonara mark.
- Shared motion primitives (`pressScale`, `revealOnAppear`, `topGlow`); Library folder cards; press feedback on shelf, top-pick and mood cards; collaborative playlist page glow, shadowed cover and animated rows.
- Offscreen screenshot renderer for the README and release notes.

---

## [1.10.6] — 2026-11-17 (`versionCode = 47`)

### Fixed
- **Google accounts that never used YouTube can join collaborative playlists**: such an account has no YouTube channel and YouTube Music refuses it as a collaborator. Joining now creates the default channel when YouTube offers to, then joins; otherwise the dialog explains and offers *Set Up YouTube Channel*.
- **Removing a song, leaving and deleting a playlist ask for confirmation**, give haptic feedback, and confirm afterwards with a notice. Failures are shown instead of swallowed.
- macOS packages are built on macOS and left exactly as Apple's tools write them; the `.pkg` is installed and the app launched on the build machine before release.

---

## [1.10.5] — 2026-11-16 (`versionCode = 46`)

### Fixed
- **Creating a collaborative playlist no longer waits 15–25 seconds**: the playlist opens as soon as YouTube Music has created it. Its invite link, which YouTube Music only mints once the new playlist has settled, is fetched in the background instead of in front of the Create button.
- **Invite links are reused**: once a playlist's link is known, *Copy Invite Link* hands it out instantly instead of asking YouTube Music to mint it again (5–10 seconds each time). The button shows progress while a link is still being prepared.
- **Song titles were unreadable in the "Add Songs" search panel** on the dark theme (only the artist and artwork showed): the title now has an explicit colour.
- **Adding a song gives feedback and shows up immediately**: the `+` turns into a spinner and then a check with haptics, the row says "In this playlist", a notice confirms the add, and the song appears in the list at once. It stays there while YouTube Music's page catches up — it used to disappear until the playlist was closed and reopened. A refused add is taken back off and explained.

---

## [1.10.4] — 2026-11-16 (`versionCode = 45`) — test build

Verified against the simulated YouTube Music environment and the unit suites only. The join,
roster and conversion changes still need a two-device check on real accounts before release.

### Fixed
- **Joining a collaborative playlist no longer fails with a raw `HTTP 401` dialog** (Android, Desktop, Web):
  - YouTube Music answers `ACTION_JOIN_COLLABORATION` with `401 "You are not authorized to edit this playlist."` both when the account is *already* a collaborator and while a newly created invite link has not propagated yet. BitChord took the first 401 as final. It now checks write access after a refusal and retries across the propagation window.
  - Write access is checked the way the web client's "Save to playlist" dialog asks (`playlist/get_add_to_playlist` with `videoIds`), plus the edit commands the playlist page itself hands a member.
  - Fallback join through the invite page: the playlist page is requested with the invite's `jct` folded into its `browse` params — the encoding music.youtube.com's own server uses — and the join command on that page is run as served.
  - A join that is really refused is explained in a sentence; response bodies are never shown.
- **Invite / Members / Sharing & Rules chips no longer wrap** into a four-line column on phones: the refresh button moved to the dialog's title row and the chip rows scroll sideways. The Editor/Viewer chooser is hidden for YouTube Music playlists, which have one collaborator role.
- **Member count follows the roster**: the `PAplaylist_collaborate` panel is requested with params built from the playlist id when the header carries none, on `WEB_REMIX` and then `WEB`.
- **"Make Collaborative" carries every song across**:
  - The source playlist is read again in full, every page — the screen starting the conversion only has the first ~100 songs loaded.
  - Songs after the first 50 could be dropped without a word while the new playlist was still settling (YouTube Music refuses edits with `409` for up to ~20 seconds). Each batch is now confirmed, the settling is waited out, and a single song YouTube Music will not accept costs only that song.
  - Converting a playlist that was already converted adds whatever the collaborative copy is missing instead of returning it short.
  - A collaborative playlist's page reads every page of its tracks, so long playlists are no longer listed short.
  - Songs that could not be copied are named in a notice rather than lost silently.
- **Playback controls missing on some phones**: the notification permission was only requested at the first download, so on Android 13+ a listener who never downloaded anything never granted it — and on several vendor skins the lock-screen player and status-bar "island" are built from the media notification. It is now requested the first time something plays, and the media notification is posted under the playback-transport category.

### Added
- **Edit a collaborative playlist's title, description and cover** (owner): tap the cover or the title, or use *Sharing & Rules → Edit title, description & cover*. Until a cover is set, a YouTube Music playlist shows the collage YouTube makes of its first four songs. Android uses the existing cover picker; Desktop opens a file dialog (JPEG/PNG, other formats are converted).

---

## [1.10.1] — 2026-11-16 (`versionCode = 42`)

### Fixed
- **Zero-Config Collaborative Playlists with Built-In Google Sign-In**:
  - Signing into BitChord with your Google / YouTube Music account (Android, Desktop, or Web companion extension) now **automatically unlocks Collaborative Playlists out of the box** — zero separate sign-in, zero `Google OAuth Client ID`, and zero custom server URL required.
  - Removed the accidental fallback of `COLLAB_SERVER` to the upstream Listen Together URL (`bitchord-listen-together.onrender.com`), which caused 404 responses and prompted users for developer OAuth credentials.
- **Native YouTube Music Collaborative Playlist Engine (`Innertube` + `CollabManager` + `ytmCollab.ts`)**:
  - Creates and converts collaborative playlists directly on YouTube Music (`ACTION_CREATE_COLLABORATION_INVITE_LINK`), mints and joins standard `https://music.youtube.com/playlist?list=...&jct=...` collaboration links (`ACTION_JOIN_COLLABORATION`), discovers existing collaborative playlists (`PAplaylist_collaborate` facepile & engagement panel), parses collaborator avatars and track attribution, and supports closing collaboration (`ACTION_SET_CLOSED_TO_CONTRIBUTIONS`) while preserving optional self-hosted PlusPC Collaboration Server mode under **Advanced Settings**.

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
