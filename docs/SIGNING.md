# Android & Desktop Signing Guide

This document explains why earlier Android updates failed with **"Invalid signature"** (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), how Sonara enforces deterministic signing across Phone, Android TV, and Wear OS, and how upgrades are verified before publication.

---

## 1. Root Cause of Historical Android "Invalid Signature" Failures

Auditing the release history and build configuration identified two independent defects that broke in-place Android upgrades:

1. **Ephemeral Keystore Generation in CI (`.github/workflows/release.yml`)**:
   - Previously, when `secrets.KEYSTORE_BASE64` was unset on a workflow run, `release.yml` executed `keytool -genkeypair` to mint a brand-new random RSA-2048 keystore on the runner.
   - Android `PackageManager` strictly requires every update (`adb install -r` or in-app `PackageInstaller` OTA) for a given `applicationId` (`com.music.bitchord` / `com.music.bitchord.tv`) to be signed with the **exact same X.509 certificate** as the installed APK. Any release signed with an ephemeral key forced users to uninstall the app and lose local app state.
2. **Inconsistent Fallback in `tv/build.gradle.kts`**:
   - While `app/build.gradle.kts` and `wearos/build.gradle.kts` used `signingConfigs.findByName("release")` (producing an unsigned APK when no release keystore was configured), `tv/build.gradle.kts` fell back to `signingConfigs.getByName("debug")`, silently signing release TV builds with machine-local debug keys that differed across machines and runners.

---

## 2. Deterministic Android Release Signing Architecture

Sonara enforces a single release signing identity across all three Android modules (`:app`, `:tv`, and `:wearos`):

- **Shared Key Requirement for Phone + Wear OS**: Google Play Services' `Wearable` Data Layer only delivers messages between `:app` (`com.music.bitchord`) and `:wearos` (`com.music.bitchord`) when both APKs share the **identical `applicationId` AND signing certificate SHA-256**.
- **Consistent Configuration**: `:app`, `:tv`, and `:wearos` resolve credentials identically from either:
  1. `keystore.properties` at the repository root (gitignored, never committed):
     ```properties
     storeFile=bitchord-release.jks
     storePassword=<secret>
     keyAlias=<alias>
     keyPassword=<secret>
     ```
  2. Environment variables (`KEYSTORE_FILE` / `BitchordReleaseStoreFile`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
- **Fail-Fast Release Gate**: Passing `-Pbitchord.requireReleaseSigning=true` (enabled in CI release workflows) aborts the Gradle build immediately if the release keystore is missing rather than ever generating an ephemeral key or falling back to a debug certificate.

---

## 3. Release Certificate Identity & Historical Upgrade Matrix

Auditing prior published releases proved that earlier workflows minted ephemeral keys that were discarded after each run:

| Release | Certificate SHA-256 Digest | Upgrade Status |
| :--- | :--- | :--- |
| Upstream `kushagrasinghx/BitChord` | `b8595722346ef38636597d6e2e2ecb8d2963fc37b183d5527e525d6955d4d921` | Separate author key — requires one-time clean install when switching to PlusPC |
| PlusPC `v1.8.1` | `1e824cdc353cae51647b61cd6ea22d5cf8ff0de312ec31fb8f85205c497cf0fe` | Ephemeral key (discarded) — requires one-time clean install to `v1.10.0` |
| PlusPC `v1.9` | `c0583a2a5e6ae3ce30b6162078179bc4c0652156d5b6f0768458efc89439a5f1` | Ephemeral key (discarded) — requires one-time clean install to `v1.10.0` |
| PlusPC `v1.9.2` | `f55db2039d70460369f0afe12c6703983fce4d9e3a9efbd943922f9ba1b25672` | Ephemeral key (discarded) — requires one-time clean install to `v1.10.0` |
| **PlusPC `v1.10.0+` (Permanent)** | **`d6d9c697c070a5ca5f4e172a13b6263fe2f3a35fff5d110596509126320c3bb2`** | **Permanent RSA-4096 key stored in GitHub Secrets (`KEYSTORE_BASE64`) — seamless in-place upgrades (`v1.10.0` → all future releases)** |

- **Permanent Subject DN**: `CN=Sonara, OU=Release Engineering, O=Inventor365, L=Unknown, ST=Unknown, C=US`
- **Permanent Certificate SHA-256**: `d6d9c697c070a5ca5f4e172a13b6263fe2f3a35fff5d110596509126320c3bb2`

Before migrating from an older ephemeral-signed release (`<= v1.9.2`), export any device-local playlists via **Library → Export (`.m3u` / `.json`)**, perform the one-time clean install of `v1.10.0`, and re-import; every subsequent PlusPC update installs directly in place without data loss.

---

## 4. Automated Upgrade & Signature Verification (`scripts/verify-android-upgrade.sh`)

Before publishing any release, run:

```bash
./scripts/verify-android-upgrade.sh build/release-1.10.0/dist \
  --expected-sha256 d6d9c697c070a5ca5f4e172a13b6263fe2f3a35fff5d110596509126320c3bb2 \
  --previous-apk path/to/previous/Sonara.apk
```

The script verifies:
1. APK Signature Scheme v2 & v3 validity via `apksigner verify --print-certs` on all 9 APKs (4 Phone ABIs + universal, 4 TV ABIs + universal, 1 Wear OS).
2. Exact certificate SHA-256 match across Phone, TV, Wear OS, and the embedded `assets/SonaraWear.apk` inside the Phone APK.
3. Monotonic `versionCode` progression (`newVersionCode > oldVersionCode`) and matching `applicationId` against `--previous-apk`.
4. Optional live device/emulator upgrade test (`--adb-device <serial>`) proving `adb install -r` succeeds without `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and preserves app data.
