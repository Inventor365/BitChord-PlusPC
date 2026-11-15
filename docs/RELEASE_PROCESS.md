# Cross-Platform Release Process

BitChord PlusPC (`https://github.com/Inventor365/BitChord-PlusPC`) ships **21 verified release artifacts** plus the Web App, Browser Extension, and Go Backend from a single versioned source tree.

---

## 1. Platform Artifact Matrix

| Platform | Target / Architecture | Artifacts |
| :--- | :--- | :--- |
| **Android Phone & Auto** | Universal + `arm64-v8a`, `armeabi-v7a`, `x86_64` | `BitChord-v<ver>.apk`, `BitChord-v<ver>_arm64-v8a.apk`, `BitChord-v<ver>_armeabi-v7a.apk`, `BitChord-v<ver>_x86_64.apk` |
| **Android TV / Fire TV** | Universal + `arm64-v8a`, `armeabi-v7a`, `x86_64` | `BitChordTV-v<ver>.apk`, `BitChordTV-v<ver>_arm64-v8a.apk`, `BitChordTV-v<ver>_armeabi-v7a.apk`, `BitChordTV-v<ver>_x86_64.apk` |
| **Wear OS 3+** | Universal (`arm64-v8a` / `armeabi-v7a`) | `BitChordWear-v<ver>.apk` *(also bundled inside the Phone APK as `assets/BitChordWear.apk`)* |
| **macOS (Apple Silicon & Intel)** | `arm64` & `x64` | `BitChord-<ver>-macos-{arm64,x64}.dmg`, `BitChord-<ver>-macos-{arm64,x64}.pkg`, `BitChord-<ver>-macos-{arm64,x64}.zip` |
| **Windows 10/11** | `x64` | `BitChord-<ver>-windows-x64-setup.exe`, `BitChord-<ver>-windows-x64.msi`, `BitChord-<ver>-windows-x64-portable.zip` |
| **Linux** | `x86_64` / `amd64` | `BitChord-<ver>-linux-x86_64.AppImage`, `BitChord-<ver>-linux-amd64.deb`, `BitChord-<ver>-linux-x86_64.rpm` |
| **Web & Extension** | Modern Browsers (Chrome / Firefox) | `web/app/dist/` static bundle + MV3/Firefox companion extension (`web/extension/`) |
| **Backend** | Linux / Docker / Go 1.27+ | `backend/` single binary (Listen Together + Presence + Collaborative Playlists + Google OIDC) |

---

## 2. Version Synchronization Checklist

Every release MUST update version metadata in lockstep across all modules (never reuse a `versionCode` for a new APK):

1. `build.gradle.kts` (root): `bitchordVersionCode` (strictly monotonic integer) and `bitchordVersionName` (e.g. `"2.0.0"`), inherited by `:app` and `:wearos`.
2. `tv/build.gradle.kts`: `versionCode` and `versionName` matching root.
3. `desktopApp/build.gradle.kts`: `appVersion` (`1.x.y` / `2.x.y`) and `desktopVersionCode` (`packageVersion` for MSI/DMG/PKG/DEB/RPM).
4. `DesktopUpdateChecker.kt`: Default fallback version string matching `appVersion`.
5. `SettingsSheet.kt`, `DesktopApp.kt`, `TvDialogs.kt`, `TvSettingsScreen.kt`: Footer version label (`bitchord <ver>`).
6. `ota.json` (in both `Inventor365/BitChord-PlusPC` and `Inventor365/Bitchord-OTA`): `version`, `versionCode`, platform download URLs, and release notes.

---

## 3. Pre-Release Verification Gates

Run every test suite and binary verifier before tagging or uploading assets:

```bash
# 1. Backend tests with race detector
(cd backend && go vet ./... && go test -race ./...)

# 2. Web & extension typecheck + unit tests + production build
(cd web && corepack pnpm -r typecheck && corepack pnpm -r test && corepack pnpm --filter @bitchord/app build)

# 3. Kotlin Multiplatform, Desktop, Android Phone, and Android TV unit tests
./gradlew :shared:jvmTest :sharedUi:jvmTest :desktopUi:jvmTest :desktopApp:test :app:testDevDebugUnitTest :tv:testDevDebugUnitTest

# 4. Android APK v2/v3 signature, certificate SHA-256 parity, and upgrade check
./scripts/verify-android-upgrade.sh build/release-<ver>/dist \
  --expected-sha256 d6d9c697c070a5ca5f4e172a13b6263fe2f3a35fff5d110596509126320c3bb2 \
  --previous-apk build/prev/BitChord.apk

# 5. macOS .zip / .dmg / .pkg structural, UDIF CRC-32, HFS+, XAR, Bom, and Mach-O CodeResources check
python3 scripts/macos/verify-macos-artifacts.py build/release-<ver>/dist --expected-version <ver>

# 6. SHA-256 checksum manifest
(cd build/release-<ver>/dist && sha256sum * > SHA256SUMS.txt)
```

---

## 4. Publishing a GitHub Release & OTA Manifest

1. Push commit and annotated tag `v<ver>` to `https://github.com/Inventor365/BitChord-PlusPC`.
2. Create the GitHub Release (`gh release create v<ver> build/release-<ver>/dist/* --repo Inventor365/BitChord-PlusPC --title "BitChord v<ver>" --notes-file docs/RELEASE_NOTES.md`).
3. Verify all 21 binary assets + `SHA256SUMS.txt` report `state == "uploaded"` via `gh release view v<ver> --repo Inventor365/BitChord-PlusPC --json assets`.
4. Update `ota.json` and `changelogs.txt` in `Inventor365/Bitchord-OTA` so existing Phone, TV, Wear OS, Windows, macOS, and Linux installations receive the verified OTA update prompt.
