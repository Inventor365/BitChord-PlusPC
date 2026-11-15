# Contributing to BitChord PlusPC

Thank you for contributing to **BitChord PlusPC** (`https://github.com/Inventor365/BitChord-PlusPC`). Follow these guidelines to keep development, code review, and cross-platform releases clean and reproducible.

## Proposing Contributions
- Substantial features, architecture changes, or cross-platform UI additions should be discussed in an issue before opening a large PR.
- Bug fixes, upstream cherry-picks (preserving original author attribution), performance improvements, and documentation updates can be submitted directly as a Pull Request.

## Branch & Attribution Policy
- Create focused feature/fix branches off `main` (for example `feat/collab-playlists`, `fix/macos-dmg-crc`).
- Keep pull requests scoped to a coherent change without unrelated formatting churn.
- When adapting or cherry-picking upstream contributions from `kushagrasinghx/BitChord`, **always preserve original author attribution** and cite the upstream PR/commit in commit messages and `CHANGELOG.md`.

## Multiplatform Development Setup
- **JDK**: JDK 21 (Eclipse Temurin 21 recommended; compiles JVM 17 bytecode across Kotlin Multiplatform modules and provides `jpackage` for Desktop installers).
- **Android SDK & NDK**: `compileSdk = 37` (`:app` / `:tv`), `targetSdk = 36`, `minSdk = 26` (`30` on `:wearos`), CMake `3.22.1+` + Android NDK for native DSP/ONNX components (`app/src/main/cpp`).
- **Web & Browser Extension (`web/`)**: Node.js 22+ and `corepack pnpm` (`pnpm 12.x`).
- **Realtime & Collaborative Backend (`backend/`)**: Go `1.27+` (`go vet ./... && go test -race ./...`).

## Build & Test Commands

### Shared KMP, Desktop, Android Phone & Android TV
```bash
# Run unit tests across shared, sharedUi, desktopUi, desktopApp, app, and tv
./gradlew :shared:jvmTest :sharedUi:jvmTest :desktopUi:jvmTest :desktopApp:test :app:testDevDebugUnitTest :tv:testDevDebugUnitTest

# Run Desktop application locally
./gradlew :desktopApp:run

# Assemble debug Android Phone APK
./gradlew :app:assembleDevDebug
```

### Web App & Companion Browser Extension
```bash
cd web
corepack pnpm install --frozen-lockfile
corepack pnpm -r typecheck
corepack pnpm -r test
corepack pnpm --filter @bitchord/app build
```

### Backend (Listen Together + Presence + Collaborative Playlists)
```bash
cd backend
go vet ./...
go test -race ./...
```

### Release Packaging & Binary Verification
- Android APK signature & upgrade compatibility check: `./scripts/verify-android-upgrade.sh <dist-dir>` (see [`docs/SIGNING.md`](docs/SIGNING.md)).
- macOS `.dmg` / `.pkg` / `.zip` packaging & structural verification: `python3 scripts/macos/verify-macos-artifacts.py <dist-dir>` (see [`docs/MACOS_PACKAGING.md`](docs/MACOS_PACKAGING.md)).
- Full release checklist: [`docs/RELEASE_PROCESS.md`](docs/RELEASE_PROCESS.md).

## Code Quality & UI Standards
- Shared domain logic belongs in `:shared`, shared Compose UI in `:sharedUi` (mirrored to `:desktopUi` for Desktop Skia linkage).
- New UI must match BitChord's first-party Apple Music glass aesthetic rather than bolting on unstyled widgets.
- Every significant behavioral fix or feature must include deterministic unit or integration tests.
- Locale strings added to `app/src/main/res/values/strings.xml` or `sharedUi`/`desktopUi` `composeResources/values/strings.xml` must be translated across all supported locale directories (`LocaleStringsTest` enforces parity).

## Audio Telemetry Honesty
Application telemetry must never misrepresent unverified hardware behavior. Distinguish source metadata, decoder format, internal DSP format, OS mixer/AudioTrack state, and physical DAC/endpoint negotiation.

## License
BitChord PlusPC is licensed under the **GNU General Public License v3.0 (GPLv3)**. All contributions and dependencies must be GPLv3-compatible.
