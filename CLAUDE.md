# BitChord PlusPC — Claude Engineering Handover & Repository Guide

## 1. Repository & Release State (Ground Truth as of 2026-11-16)

- **GitHub Repository**: `https://github.com/Inventor365/BitChord-PlusPC.git` (`origin`, branch `main`)
- **OTA Manifest Repository**: `https://github.com/Inventor365/Bitchord-OTA.git` (currently serving **`v1.10.1`**, `versionCode = 42`)
- **Latest Published GitHub Release**: **`v1.10.1`** (`versionCode = 42`, all 21 platform binaries + `SHA256SUMS.txt` uploaded)
- **Current Working Version**: **`1.10.5` (`versionCode = 46`, `desktopVersionCode = 46`)**
  - Signed test APKs: `/serverhive2/yukia/bitchord/build/apk-preview-1.10.5/`, verified with `scripts/verify-android-upgrade.sh` against `build/apk-preview-1.10.4/`.
  - The user sideloaded **`1.10.4`** and confirmed collaborative playlists (create, invite, join) work on real devices. Once `1.10.5` has been sideloaded, bump to **`1.10.6` (`versionCode = 47`)** for the next test APK.
  - Do **not** push to `Inventor365/Bitchord-OTA` until the matching GitHub Release APKs/installers are uploaded on `Inventor365/BitChord-PlusPC`, otherwise live users' OTA checks would 404 on the download URL.
  - **Known hazard**: root `ota.json` on `main` advertises `1.10.3`, which has no GitHub Release. `AppUpdateChecker` / `DesktopUpdateChecker` use `raw.githubusercontent.com/Inventor365/BitChord-PlusPC/main/ota.json` as a fallback source, so if the OTA repository is unreachable live users are offered a download that 404s. It was left as found; decide whether to set it back to `1.10.1` until a release exists.

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

## 3. Collaborative Playlists — What Was Fixed in `1.10.4` and What Still Needs Real Devices

`1.10.4` changes are verified against `SimulatedInnertubeCollabEnvTest`, `CollabInnertubeProtocolTest` and the web `collab.test.ts` only. **Nothing here has been run against real YouTube Music accounts yet** — do the two-device check below before releasing.

### Verified Protocol Facts (checked against the live service or real captured responses — rely on these)
- **The join call is right**: `browse/edit_playlist` with `{"action": "ACTION_JOIN_COLLABORATION", "joinCollaborationToken": <jct>}` is exactly what `ytmusicapi` (`join_collaborative_playlist`) sends and tests against real accounts.
- **`401 "You are not authorized to edit this playlist."` is ambiguous.** `ytmusicapi` documents it as the answer to an account that is *already* a collaborator; its tests also wait ~15s after enabling collaboration before joining. So a 401 must be followed by a write-access check and a retry, never shown as final (`Innertube.joinCollaborativePlaylist`).
- **How music.youtube.com itself consumes an invite link**: the web client has no `jct` handling in JavaScript. The *server* folds `jct` into the playlist page's `browse` params: protobuf field 39 = token, then fixed bytes `C2 05 03 08 D8 04`, URL-safe base64 with `=` as `%3D`. Captured from the live server (e.g. `jct=AbCdEfGhIjKlMnOpQrStUv` -> `ugIWQWJDZEVmR2hJaktsTW5PcFFyU3RVdsIFAwjYBA%3D%3D`); implemented as `Innertube.collaborationInviteBrowseParams` / `ytmInviteBrowseParams`. The page then carries a dialog (`collaborativePlaylistInviteMessageViewModel`) whose button holds the join command.
  - **Not verified**: the exact shape of that dialog's join command for a signed-in non-member (it needs a real session to see). `InnertubeParser.parseCollaborationJoinCommands` reads a `playlistEditEndpoint` whose actions join, or a POST command named like a collaboration join; if the real shape differs, capture it and extend the parser.
- **Collaborators panel params are just the playlist id**: protobuf field 138 `{1: playlistId}` (real header sample from `ytmusicapi` test data: `PLxyTaDz8f5PBc-8kE36gvB-eflhODG2dw` -> `0ggkCiJQTHh5VGFEejhmNVBCYy04a0UzNmd2Qi1lZmxoT0RHMmR3`). `Innertube.collaboratorsPanelParams` builds them when the header has none.
- **Panel shape** (from the web client's renderer): `playlistCollaborationViewModel.playlistCollaborators[].contentListItemViewModel { title.content, avatar.avatarViewModel, rendererContext }`.
- **A just-created playlist refuses edits** (`409`, sometimes `400` for collaboration) for up to ~20s (`ytmusicapi` `retry_playlist_edit`). `CollabManager.addSongsReliably` waits that out.

### Two-Device Check To Run on `1.10.4`
1. **Fresh join**: owner creates a collaborative playlist and an invite; a second Google account joins from BitChord. Expect success within ~20s and `Members (2)` on the owner's device.
2. **Re-join**: the second account taps the same invite again (or from a reinstall). Expect "already a member", not an error.
3. **Stale link**: owner taps *Create & Copy Invite Link* again, then the old link is used. Check whether YouTube Music still honours the old token and what the dialog says.
4. **Convert a playlist of 150+ songs** and confirm the song count matches the source.
5. **Rename and change the cover** as owner; confirm the second device sees both.
6. If a join still fails, capture the response of `browse` with the invite params for a non-member — that shows the real join command.

### Not Done (deliberate)
- No WebView join fallback on Android. If API joins turn out to be gated for some accounts, the fallback is a `WebView` on `https://music.youtube.com/playlist?list=<id>&jct=<token>` with the app's Google cookies.
- "Make Collaborative" still makes a copy rather than enabling collaboration on the original playlist in place.
- Phones whose status-bar "island" only shows media from an allow-list of apps cannot be fixed from inside the app.

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
