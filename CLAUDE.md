# BitChord PlusPC — Claude Engineering Handover & Repository Guide

## 1. Repository & Release State (Ground Truth as of 2026-11-16)

- **GitHub Repository**: `https://github.com/Inventor365/BitChord-PlusPC.git` (`origin`, branch `main`)
- **OTA Manifest Repository**: `https://github.com/Inventor365/Bitchord-OTA.git` (currently serving **`v1.10.1`**, `versionCode = 42`)
- **Latest Published GitHub Release**: **`v1.10.1`** (`versionCode = 42`, all 21 platform binaries + `SHA256SUMS.txt` uploaded)
- **Current `main` Working Version**: **`1.10.3` (`versionCode = 44`, `desktopVersionCode = 44`)**
  - **IMPORTANT FOR NEXT APK BUILD**: The user has already sideloaded **`1.10.3` (`versionCode = 44`)** onto their physical test phones (from `/serverhive2/yukia/bitchord/build/apk-preview-1.10.3/`). Bump to **`1.10.4` (`versionCode = 45`)** when building the next test APK so Android upgrades in-place without `INSTALL_FAILED_VERSION_DOWNGRADE`.
  - Do **not** push to `Inventor365/Bitchord-OTA` until the matching GitHub Release APKs/installers are uploaded on `Inventor365/BitChord-PlusPC`, otherwise live users' OTA checks would 404 on the download URL.

---

## 2. Workstation Environment & Permanent Signing Key (CRITICAL)

- **JDK 21**: `export JAVA_HOME=/serverhive2/yukia/.local/jdk-21`
- **Android SDK**: `export ANDROID_HOME=/serverhive2/yukia/android-sdk` and `export ANDROID_SDK_ROOT=/serverhive2/yukia/android-sdk`
- **Host Packaging Tools** (`rpmbuild`, `bsdtar`, `bsdcpio`, `fsck.hfsplus`, `msiinfo`, `msibuild`, `gcab`, `mksquashfs`, `rcodesign`, `appimagetool.AppImage`):
  `export PATH="/serverhive2/yukia/bitchord/build/host-tools/bin:$JAVA_HOME/bin:$PATH"`
- **Permanent Android Release Keystore (NEVER regenerate or overwrite!)**:
  - Keystore file: `/serverhive2/yukia/.config/bitchord-signing/bitchord-pluspc-release.jks` (mode `600`, outside git worktree)
  - Credentials: `/serverhive2/yukia/.config/bitchord-signing/credentials.env` and gitignored root `/serverhive2/yukia/bitchord/keystore.properties`
  - **Expected Release Certificate SHA-256**:
    `d6d9c697c070a5ca5f4e172a13b6263fe2f3a35fff5d110596509126320c3bb2`
  - Always verify built APKs before handing off or releasing:
    ```bash
    bash scripts/verify-android-upgrade.sh \
      --previous-apk build/apk-preview-1.10.3/BitChord-v1.10.3.apk \
      <output-apk-dir>
    ```
  - Note: `desktopUi` mirrors `sharedUi` collaborative UI at `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/collab/CollabPlaylistsUi.kt`. Whenever you edit `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/collab/CollabPlaylistsUi.kt`, copy/sync it to `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/collab/CollabPlaylistsUi.kt`.

---

## 3. Immediate Open Issues to Fix Next (With Exact Evidence & Root-Cause Blueprint)

Two real-device screenshots from `v1.10.3` (`versionCode = 44`) capture the exact remaining issues in **Zero-Config YouTube Music Collaborative Playlists**:
- `/tmp/f14a00fa-b17f-492b-aa0c-4e684ff7994c.jpg` (Joining device error on **Accept & Join Playlist**)
- `/tmp/02c5d104-6f33-4eb0-9fae-941580caea4c.png` (Owner device **Invite / Members / Sharing & Rules** modal)

### What Already Works Confirmed on Real Devices
- Creating collaborative playlists on YouTube Music (including emoji titles & blank descriptions) works.
- Minting real YouTube Music collaboration invite links (`https://music.youtube.com/playlist?list=PLErqBdLwkATE&jct=L1_yAKqFkzT...`) works.
- Direct `bitchord://collab/ytm/<playlistId>?jct=<token>` link generation & clipboard auto-preview work.

---

### Issue A — Joining via BitChord (`Accept & Join Playlist`) Fails With `HTTP 401 UNAUTHENTICATED: "You are not authorized to edit this playlist."`
**Screenshot**: `/tmp/f14a00fa-b17f-492b-aa0c-4e684ff7994c.jpg`

**Observed Error in `CollabJoinInviteDialog`**:
```json
Client request (POST https://music.youtube.com/youtubei/v1/browse/edit_playlist?prettyPrint=false&hl=en) invalid: 401.
Text: "{
  "error": {
    "code": 401,
    "message": "You are not authorized to edit this playlist.",
    "errors": [{
      "message": "You are not authorized to edit this playlist.",
      "domain": "global",
      "reason": "unauthorized"
    }],
    "status": "UNAUTHENTICATED"
  }
}"
```

**Relevant Files**:
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/Innertube.kt` (`joinCollaborativePlaylist` lines `1378–1458`, `isEditablePlaylistForCurrentUser` lines `1355–1374`, `postYouTubeWeb` lines `1515–1562`)
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnertubeParser.kt` (`parseAddToPlaylistIds` lines `2046–2056`)
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/collab/CollabManager.kt` (`acceptInvite` lines `890–916`)

**Exact Root Causes & Fix Plan**:
1. **Case 1 — Joining Device Is *Already* a Collaborator (`HTTP 401` is YouTube's documented "already joined" response!)**:
   - `ytmusicapi` (`ytmusicapi/mixins/playlists.py`, `join_collaborative_playlist`) explicitly documents:
     > *"If you're already a collaborator, an Unauthorized (401) server error is raised."*
   - In `Innertube.joinCollaborativePlaylist`, when `editPlaylist` (`ACTION_JOIN_COLLABORATION`) threw Ktor `ClientRequestException` (`401`), two things went wrong:
     1. `isEditablePlaylistForCurrentUser(rawId)` called `postMusic("playlist/get_add_to_playlist") { put("playlistId", "LM") }`. On `WEB_REMIX`, `get_add_to_playlist` expects `putJsonArray("videoIds") { add("dQw4w9WgXcQ") }` (not `"playlistId": "LM"`, or check both!) and can wrap playlist IDs in `addToPlaylistItemRenderer` / `musicTwoRowItemRenderer` / `playlistAddToOptionRenderer.serviceEndpoint.playlistEditEndpoint.playlistId`. Because `isEditablePlaylistForCurrentUser` returned `false`, the code did not recognize that the user was already a collaborator!
     2. In `Innertube.joinCollaborativePlaylist` line `1430`, `retryable` checked `400`, `403`, `409`, `500`, `503` — **not `401`** — so it broke out of the loop immediately and threw the raw Ktor `401` JSON string into the UI!
2. **Case 2 — Joining a Fresh Collaborative Playlist Where the User Is *Not* Yet a Collaborator (`WEB_REMIX` vs `WEB` / Web Page Endpoint)**:
   - Remember the user's earlier observation: *"if the invite link is clicked - it opens youtube music and if a person adds this playlist to youtube music, bitchord shows them added"*.
   - Verify whether `music.youtube.com/youtubei/v1/browse/edit_playlist` (`WEB_REMIX`, client ID `67`) rejects `ACTION_JOIN_COLLABORATION` with `401 You are not authorized to edit this playlist` for non-owners whereas **`www.youtube.com/youtubei/v1/browse/edit_playlist` (`WEB` client via `postYouTubeWeb`, client ID `1`, `Origin: https://www.youtube.com`)** OR the `serviceEndpoint` inside `GET https://music.youtube.com/playlist?list=$rawId&jct=$cleanJct` / `GET https://www.youtube.com/playlist?list=$rawId&jct=$cleanJct` (`ytInitialData` — look for `ACTION_JOIN_COLLABORATION` or `playlistEditEndpoint.params` or `confirmDialogRenderer` accept button command!) is what actually executes the join!
   - **Recommended multi-strategy join in `Innertube.joinCollaborativePlaylist`**:
     1. Try `WEB_REMIX` `browse/edit_playlist` (`ACTION_JOIN_COLLABORATION`).
     2. If `401` / `403` / `400`, try **`postYouTubeWeb("browse/edit_playlist")`** (`https://www.youtube.com/youtubei/v1/browse/edit_playlist` with `clientName = "WEB"`, `SAPISIDHASH` for `https://www.youtube.com`) with `ACTION_JOIN_COLLABORATION`!
     3. Also fetch `GET https://www.youtube.com/playlist?list=$rawId&jct=$cleanJct` (with `Cookie` + `WEB_USER_AGENT`) and parse `ytInitialData` for any `playlistEditEndpoint` (`params` + `actions` / `clientParams`) or `serviceEndpoint` attached to the `"Collaborate"` / `"Join"` button and execute that exact endpoint!
     4. Verify write access afterward via `playlist/get_add_to_playlist` with `videoIds = ["dQw4w9WgXcQ"]` (and/or check if the current user's channel appears in `getCollaboratorsPanel`), and if on Android all API-only join paths are gated by YouTube, offer an optional 1-tap offscreen/sheet `WebView` fallback loading `https://music.youtube.com/playlist?list=$rawId&jct=$cleanJct` with the user's existing `BrowserSession.installGoogleCookies(cookie)`.
     5. Sanitize Ktor `ClientRequestException` messages so raw JSON payloads are never dumped onto dialogs.

---

### Issue B — `CollabInviteAndManageDialog` Tab Chip Wrapping & Live Collaborator Count (`Members (1)`)
**Screenshot**: `/tmp/02c5d104-6f33-4eb0-9fae-941580caea4c.png`

1. **UI Layout Wrap on `"Sharing & Rules"` Chip**:
   - **File**: `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/collab/CollabPlaylistsUi.kt` lines `1474–1490` (and mirror `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/collab/CollabPlaylistsUi.kt`).
   - **Cause**: `Row` contains 3 `FilterChip`s (`Invite Link`, `Members (1)`, `Sharing & Rules`) + `Spacer(Modifier.weight(1f))` + `Refresh` `IconButton` without `horizontalScroll`, squeezing `"Sharing & Rules"` into 4 vertical lines (`Shari / ng & / Rule / s`).
   - **Fix**: Move the `Refresh` `IconButton` up into the modal title `Row` (next to the `Close` `X` icon at line `1463`) and add `.horizontalScroll(rememberScrollState())` + `maxLines = 1, softWrap = false` on the `FilterChip` labels.
2. **Owner Member Count & Roster Sync (`Members (1)` -> `Members (2)`)**:
   - Once Device B genuinely joins (fixing Issue A), verify `Innertube.getCollaboratorsPanel` (`Innertube.kt:1485–1507`) and `InnertubeParser.parseCollaboratorsPanel` (`InnertubeParser.kt:1943–1979`) against real `WEB` `get_panel` responses:
     - Note that on YouTube `WEB`, `POST /youtubei/v1/get_panel` can wrap `playlistCollaborationViewModel` inside `actions[0].updateEngagementPanelAction.content.sectionListRenderer...` or `engagementPanels[0].engagementPanelSectionListRenderer.content...` (`collectRenderers` already walks the full JSON tree, so verify `params` passed to `get_panel` — on `WEB`, `browse("VL$rawId")` via `postYouTubeWeb("browse")` may carry a `WEB`-specific `showEngagementPanelEndpoint.globalConfiguration.params` distinct from `WEB_REMIX`'s `params`!).
     - Calling `postYouTubeWeb("browse") { put("browseId", "VL$rawId") }` when `meta.isCollaborative` is true lets you read both the `WEB` header facepile/collaborator dialog (`DialogView` -> `ListView` -> `ListItemView`, see `LuanRT/YouTube.js` commit `049f9a6`) AND the `WEB`-scoped `panelParams` for `postYouTubeWeb("get_panel")`!

---

## 4. Architecture & Key Files Map

| Area | Key Files |
|---|---|
| **Innertube Client & Parser** | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/Innertube.kt`, `InnertubeParser.kt`, `YtMusicRepository.kt` |
| **Collaborative Playlists Engine** | `shared/src/commonMain/kotlin/com/music/bitchord/data/collab/CollabModels.kt`, `CollabInviteLink.kt`<br/>`shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/collab/CollabManager.kt`, `CollabClient.kt`, `PkceGoogleAuth.kt` |
| **Shared Compose Collab UI** | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/collab/CollabPlaylistsUi.kt`<br/>`desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/collab/CollabPlaylistsUi.kt` (keep identical) |
| **Android App Wiring & Deep Links** | `app/src/main/java/com/music/bitchord/collab/AndroidCollab.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `ui/screens/SettingsSheet.kt` |
| **Desktop App Wiring** | `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopCollab.kt`, `DesktopApp.kt` |
| **Web App Collab Engine & UI** | `web/app/src/collab/{ytmCollab.ts,auth.ts,client.ts,config.ts,inviteLink.ts,collab.test.ts}`, `components/collab/{CollabModals.tsx,CollabSignInModal.tsx}` |
| **Optional Go Collab Backend** | `backend/collab/{store.go,http.go,ws.go}`, `backend/auth/oidc.go` |
| **Virtual Simulator & Tests** | `shared/src/jvmTest/kotlin/com/music/bitchord/data/collab/SimulatedInnertubeCollabEnvTest.kt`, `CollabClientAndModelsTest.kt` |
| **Packaging & Verification** | `scripts/verify-android-upgrade.sh`, `scripts/macos/package-macos.py`, `scripts/macos/verify-macos-artifacts.py`, `docs/SIGNING.md`, `docs/MACOS_PACKAGING.md`, `docs/RELEASE_PROCESS.md` |

---

## 5. Build, Test & Verification Commands

### Run All Unit & Virtual Simulated Tests
```bash
export JAVA_HOME=/serverhive2/yukia/.local/jdk-21
export PATH="/serverhive2/yukia/bitchord/build/host-tools/bin:$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/serverhive2/yukia/android-sdk
export ANDROID_SDK_ROOT=/serverhive2/yukia/android-sdk

# Kotlin / Android / Desktop / TV unit + simulated 2-device Innertube suites
./gradlew :shared:jvmTest :sharedUi:jvmTest :desktopUi:jvmTest :desktopApp:test :app:testDevDebugUnitTest :tv:testDevDebugUnitTest \
  -Dorg.gradle.java.installations.paths=/serverhive2/yukia/.local/jdk-21 --console=plain

# Web typecheck + Vitest + Vite build
(cd web && pnpm -r typecheck && pnpm -r test && VITE_PARTY_SERVER_URL=https://party.example.com pnpm --filter @bitchord/app build)

# Go backend race tests
(cd backend && go vet ./... && go test -race ./...)
```

### Build & Cryptographically Verify Signed Android APKs
```bash
./gradlew :wearos:assembleProdRelease :app:assembleProdRelease :tv:assembleProdRelease \
  -Pbitchord.requireReleaseSigning=true \
  -Dorg.gradle.java.installations.paths=/serverhive2/yukia/.local/jdk-21 --console=plain

bash scripts/verify-android-upgrade.sh \
  --previous-apk build/apk-preview-1.10.3/BitChord-v1.10.3.apk \
  app/build/outputs/apk/prod/release
```

### Build Desktop Packages (Linux, Windows, macOS)
- **Windows target selector**: `desktopUi/build.gradle.kts` switches to Compose `1.10.3` / `skiko 0.9` when `-Pbitchord.target=windows` is passed, and `1.12.0` / `skiko 0.150.1` for Linux & macOS.
- **macOS deterministic repacker**: `python3 scripts/macos/package-macos.py --version <ver> --arch <arm64|x64> --base-dir <prior-dist> --jars-dir <mac-jars> --output-dir <out-dir>` (supports multi-extent HFS+ allocation, `fsck.hfsplus -fn`, XAR `0x78 0xda` zlib + POSIX `cksum` `Bom`, and Mach-O `LC_CODE_SIGNATURE` + `CodeResources` verification via `scripts/macos/verify-macos-artifacts.py`).
