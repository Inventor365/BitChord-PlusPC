# BitChord PlusPC `v1.10.1` (`versionCode = 42`)

**Zero-Config Google Collaborative Playlists Update — Android, Android Auto, Android TV, Wear OS, macOS (`arm64` & `x64`), Windows (`x64`), Linux (`x86_64`) & Web**

---

## What's New in `v1.10.1`

### 1. Signing into BitChord with Google Now Unlocks Collaborative Playlists Automatically
- **Zero Extra Login, Zero OAuth Client ID, Zero Server Setup**: Your existing BitChord Google / YouTube Music sign-in (on Android, Desktop, and Web) now powers **Collaborative Playlists** out of the box!
- **Native YouTube Music Collaboration (`?list=…&jct=…`)**:
  - Create new collaborative playlists or convert any personal, local, Desktop, or imported Spotify playlist via **Make Collaborative** (`ACTION_CREATE_COLLABORATION_INVITE_LINK`) while keeping your original playlist untouched.
  - Generate and copy real `https://music.youtube.com/playlist?list=…&jct=…` invite links that friends can join in **BitChord** (Android, Desktop, Web) or **YouTube Music** (`ACTION_JOIN_COLLABORATION`).
  - Automatically discovers collaborative playlists already in your YouTube Music library (`PAplaylist_collaborate`), displays collaborator avatars & contributor attribution, syncs changes live, and supports **Stop Sharing (Make Private)** (`ACTION_SET_CLOSED_TO_CONTRIBUTIONS`).
  - Optional self-hosted PlusPC Collaboration Server mode (`backend/`) remains available under **Advanced: Use a self-hosted Collaboration Server**.

### 2. Verified Seamless In-Place Upgrade from `v1.10.0`
- Signed with the permanent BitChord PlusPC RSA-4096 release certificate (`SHA-256: d6d9c697c070a5ca5f4e172a13b6263fe2f3a35fff5d110596509126320c3bb2`) and verified against `v1.10.0` (`versionCode = 41 -> 42`) via `scripts/verify-android-upgrade.sh --previous-apk`.

---

## Verified Release Assets (21 Packages + `SHA256SUMS.txt`)
- **Android Phone & Auto (4)**: `BitChord-v1.10.1.apk`, `BitChord-v1.10.1_arm64-v8a.apk`, `BitChord-v1.10.1_armeabi-v7a.apk`, `BitChord-v1.10.1_x86_64.apk`
- **Android TV / Fire TV (4)**: `BitChordTV-v1.10.1.apk`, `BitChordTV-v1.10.1_arm64-v8a.apk`, `BitChordTV-v1.10.1_armeabi-v7a.apk`, `BitChordTV-v1.10.1_x86_64.apk`
- **Wear OS (1)**: `BitChordWear-v1.10.1.apk`
- **macOS Apple Silicon & Intel (6)**: `BitChord-1.10.1-macos-{arm64,x64}.{dmg,pkg,zip}` *(ad-hoc signed — right-click → Open or `xattr -cr /Applications/BitChord.app` on first launch)*
- **Windows x64 (3)**: `BitChord-1.10.1-windows-x64-setup.exe`, `BitChord-1.10.1-windows-x64.msi`, `BitChord-1.10.1-windows-x64-portable.zip`
- **Linux x86_64 (3)**: `BitChord-1.10.1-linux-x86_64.AppImage`, `BitChord-1.10.1-linux-amd64.deb`, `BitChord-1.10.1-linux-x86_64.rpm`
