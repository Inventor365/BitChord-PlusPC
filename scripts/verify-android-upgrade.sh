#!/usr/bin/env bash
#
# Verifies Android release APK signatures (v2 + v3), certificate SHA-256 consistency
# across Phone / Wear OS / Android TV APKs, embedded BitChordWear.apk integrity, and
# in-place upgrade compatibility (static + optional live adb install -r data retention).

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/verify-android-upgrade.sh [options] [apk-dir-or-file...]

Options:
  --apk-dir <dir>                 Directory containing APKs to verify (default: dist if no positional paths given)
  --expected-cert-sha256 <hex>    Expected 64-hex Signer #1 certificate SHA-256 digest
                                  (defaults to $EXPECTED_ANDROID_CERT_SHA256 if set)
  --previous-apk <old.apk>        Previous release APK to verify upgrade compatibility against (repeatable)
  --target-apk <new.apk>          Explicit new APK to compare against --previous-apk (auto-matched by package if omitted)
  --adb-device <serial>           With --previous-apk, perform live install-old -> seed -> adb install -r -> retention check
  -h, --help                      Show this help message
EOF
}

normalize_sha256() {
  local raw="${1:-}"
  raw="$(printf '%s' "$raw" | tr -d ' :[:space:]' | tr '[:upper:]' '[:lower:]')"
  printf '%s' "$raw"
}

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print tolower($1)}'
  else
    python3 - "$file" <<'PY'
import hashlib, sys
h = hashlib.sha256()
with open(sys.argv[1], "rb") as f:
    for chunk in iter(lambda: f.read(65536), b""):
        h.update(chunk)
print(h.hexdigest())
PY
  fi
}

discover_build_tool() {
  local tool_name="$1"
  local override_val="${2:-}"

  if [[ -n "$override_val" && -x "$override_val" ]]; then
    printf '%s\n' "$override_val"
    return 0
  fi

  local sdk_roots=()
  [[ -n "${ANDROID_SDK_ROOT:-}" ]] && sdk_roots+=("$ANDROID_SDK_ROOT")
  [[ -n "${ANDROID_HOME:-}" ]] && sdk_roots+=("$ANDROID_HOME")
  sdk_roots+=(
    "$HOME/Android/Sdk"
    "$HOME/android-sdk"
    "$HOME/Library/Android/sdk"
    "/usr/local/lib/android/sdk"
    "/usr/lib/android-sdk"
    "/opt/android-sdk"
  )

  local root bt_dir candidate
  for root in "${sdk_roots[@]}"; do
    [[ -d "$root/build-tools" ]] || continue
    while IFS= read -r bt_dir; do
      [[ -n "$bt_dir" ]] || continue
      candidate="$root/build-tools/$bt_dir/$tool_name"
      if [[ -x "$candidate" ]]; then
        printf '%s\n' "$candidate"
        return 0
      fi
    done < <(ls -1 "$root/build-tools" 2>/dev/null | sort -rV)
  done

  if command -v "$tool_name" >/dev/null 2>&1; then
    command -v "$tool_name"
    return 0
  fi

  return 1
}

discover_adb() {
  if [[ -n "${ADB:-}" && -x "${ADB}" ]]; then
    printf '%s\n' "$ADB"
    return 0
  fi
  local root
  for root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Android/Sdk" "$HOME/android-sdk" "$HOME/Library/Android/sdk" "/usr/local/lib/android/sdk" "/opt/android-sdk"; do
    [[ -n "$root" && -x "$root/platform-tools/adb" ]] && { printf '%s\n' "$root/platform-tools/adb"; return 0; }
  done
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi
  return 1
}

INPUT_PATHS=()
PREVIOUS_APKS=()
TARGET_APK=""
ADB_DEVICE=""
EXPECTED_CERT_SHA256="${EXPECTED_ANDROID_CERT_SHA256:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk-dir)
      [[ $# -ge 2 ]] || { echo "error: --apk-dir requires a path" >&2; exit 2; }
      INPUT_PATHS+=("$2")
      shift 2
      ;;
    --expected-cert-sha256)
      [[ $# -ge 2 ]] || { echo "error: --expected-cert-sha256 requires a hex digest" >&2; exit 2; }
      EXPECTED_CERT_SHA256="$2"
      shift 2
      ;;
    --previous-apk)
      [[ $# -ge 2 ]] || { echo "error: --previous-apk requires an APK path" >&2; exit 2; }
      PREVIOUS_APKS+=("$2")
      shift 2
      ;;
    --target-apk)
      [[ $# -ge 2 ]] || { echo "error: --target-apk requires an APK path" >&2; exit 2; }
      TARGET_APK="$2"
      shift 2
      ;;
    --adb-device)
      [[ $# -ge 2 ]] || { echo "error: --adb-device requires a device serial" >&2; exit 2; }
      ADB_DEVICE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      while [[ $# -gt 0 ]]; do
        INPUT_PATHS+=("$1")
        shift
      done
      ;;
    -*)
      echo "error: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      INPUT_PATHS+=("$1")
      shift
      ;;
  esac
done

if [[ ${#INPUT_PATHS[@]} -eq 0 ]]; then
  INPUT_PATHS=("dist")
fi

EXPECTED_CERT_SHA256="$(normalize_sha256 "$EXPECTED_CERT_SHA256")"
if [[ -n "$EXPECTED_CERT_SHA256" && ! "$EXPECTED_CERT_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: --expected-cert-sha256 must be a 64-character hex SHA-256 digest (got '$EXPECTED_CERT_SHA256')" >&2
  exit 2
fi

APKSIGNER_BIN="$(discover_build_tool apksigner "${APKSIGNER:-}")" || {
  echo "error: could not locate 'apksigner' in ANDROID_SDK_ROOT, ANDROID_HOME, or PATH" >&2
  exit 1
}
AAPT2_BIN="$(discover_build_tool aapt2 "${AAPT2:-}")" || {
  echo "error: could not locate 'aapt2' in ANDROID_SDK_ROOT, ANDROID_HOME, or PATH" >&2
  exit 1
}

echo "Using apksigner: $APKSIGNER_BIN"
echo "Using aapt2:     $AAPT2_BIN"

APKS=()
for p in "${INPUT_PATHS[@]}"; do
  if [[ -d "$p" ]]; then
    while IFS= read -r found_apk; do
      [[ -n "$found_apk" ]] && APKS+=("$found_apk")
    done < <(find "$p" -maxdepth 1 -type f -name '*.apk' | sort)
  elif [[ -f "$p" ]]; then
    APKS+=("$p")
  else
    echo "error: path does not exist: $p" >&2
    exit 1
  fi
done

if [[ ${#APKS[@]} -eq 0 ]]; then
  echo "error: no .apk files found in: ${INPUT_PATHS[*]}" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# Inspects an APK and populates global OUT_PKG, OUT_VCODE, OUT_VNAME, OUT_V2, OUT_V3, OUT_CERT.
inspect_apk() {
  local apk="$1"
  local require_v2_v3="${2:-true}"
  local sig_out badging_out pkg_line

  if ! sig_out="$("$APKSIGNER_BIN" verify --verbose --print-certs "$apk" 2>&1)"; then
    echo "FAIL: apksigner verification failed for $apk:" >&2
    echo "$sig_out" >&2
    return 1
  fi

  OUT_V2="$(printf '%s\n' "$sig_out" | sed -n 's/^Verified using v2 scheme (APK Signature Scheme v2):[[:space:]]*\([a-z]*\).*/\1/p' | head -n1)"
  OUT_V3="$(printf '%s\n' "$sig_out" | sed -n 's/^Verified using v3 scheme (APK Signature Scheme v3):[[:space:]]*\([a-z]*\).*/\1/p' | head -n1)"
  OUT_CERT="$(printf '%s\n' "$sig_out" | sed -n 's/^\(Signer #1\|V[0-9.]* Signer:\) certificate SHA-256 digest:[[:space:]]*\([0-9A-Fa-f:[:space:]]*\)$/\2/p' | head -n1)"
  OUT_CERT="$(normalize_sha256 "$OUT_CERT")"

  if ! badging_out="$("$AAPT2_BIN" dump badging "$apk" 2>&1)"; then
    echo "FAIL: aapt2 dump badging failed for $apk:" >&2
    echo "$badging_out" >&2
    return 1
  fi

  pkg_line="$(printf '%s\n' "$badging_out" | grep -m1 '^package: ' || true)"
  OUT_PKG="$(printf '%s\n' "$pkg_line" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
  OUT_VCODE="$(printf '%s\n' "$pkg_line" | sed -n "s/.* versionCode='\([^']*\)'.*/\1/p")"
  OUT_VNAME="$(printf '%s\n' "$pkg_line" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")"
  local min_sdk
  min_sdk="$(printf '%s\n' "$badging_out" | sed -n "s/^\(minS\|s\)dkVersion:'\([0-9]*\)'.*/\2/p" | head -n1)"
  min_sdk="${min_sdk:-26}"

  if [[ "$require_v2_v3" == "true" ]]; then
    if (( min_sdk < 28 )) && [[ "$OUT_V2" != "true" ]]; then
      echo "FAIL: $apk (minSdk=$min_sdk) is NOT signed with APK Signature Scheme v2 (got '${OUT_V2:-missing}')" >&2
      return 1
    fi
    if [[ "$OUT_V3" != "true" ]]; then
      echo "FAIL: $apk is NOT signed with APK Signature Scheme v3 (got '${OUT_V3:-missing}')" >&2
      return 1
    fi
  fi

  if [[ ! "$OUT_CERT" =~ ^[0-9a-f]{64}$ ]]; then
    echo "FAIL: could not extract Signer #1 certificate SHA-256 digest for $apk" >&2
    echo "$sig_out" >&2
    return 1
  fi
  if [[ -z "$OUT_PKG" || -z "$OUT_VCODE" || -z "$OUT_VNAME" ]]; then
    echo "FAIL: incomplete badging metadata for $apk ($pkg_line)" >&2
    return 1
  fi
}

extract_zip_entry() {
  local archive="$1"
  local entry="$2"
  local dest="$3"
  python3 - "$archive" "$entry" "$dest" <<'PY'
import sys, zipfile
archive, entry, dest = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(archive, "r") as zf:
    if entry not in zf.namelist():
        sys.exit(2)
    with zf.open(entry, "r") as src, open(dest, "wb") as out:
        while True:
            buf = src.read(65536)
            if not buf:
                break
            out.write(buf)
PY
}

declare -a APK_FILES=()
declare -a APK_PKGS=()
declare -a APK_VCODES=()
declare -a APK_VNAMES=()
declare -a APK_CERTS=()

PHONE_CERT=""
WEAR_CERT=""
TV_CERT=""
COMMON_CERT=""
EMBEDDED_WEAR_VERIFIED=0

echo ""
echo "=== Verifying Android Release APKs (${#APKS[@]} file(s)) ==="
for apk in "${APKS[@]}"; do
  inspect_apk "$apk" "true"
  APK_FILES+=("$apk")
  APK_PKGS+=("$OUT_PKG")
  APK_VCODES+=("$OUT_VCODE")
  APK_VNAMES+=("$OUT_VNAME")
  APK_CERTS+=("$OUT_CERT")

  printf '  [OK] %-44s pkg=%-22s vCode=%-5s vName=%-10s v2=%s v3=%s sha256=%s\n' \
    "$(basename "$apk")" "$OUT_PKG" "$OUT_VCODE" "$OUT_VNAME" "$OUT_V2" "$OUT_V3" "$OUT_CERT"

  if [[ -z "$COMMON_CERT" ]]; then
    COMMON_CERT="$OUT_CERT"
  elif [[ "$OUT_CERT" != "$COMMON_CERT" ]]; then
    echo "FAIL: certificate SHA-256 mismatch across release APKs!" >&2
    echo "  expected: $COMMON_CERT (${APK_FILES[0]})" >&2
    echo "  actual:   $OUT_CERT ($apk)" >&2
    exit 1
  fi

  if [[ -n "$EXPECTED_CERT_SHA256" && "$OUT_CERT" != "$EXPECTED_CERT_SHA256" ]]; then
    echo "FAIL: $apk certificate SHA-256 ($OUT_CERT) does not match EXPECTED_ANDROID_CERT_SHA256 ($EXPECTED_CERT_SHA256)" >&2
    exit 1
  fi

  base_name="$(basename "$apk")"
  if [[ "$base_name" == *Wear* || "$base_name" == *wear* ]]; then
    if [[ "$OUT_PKG" != "com.music.bitchord" && "$OUT_PKG" != "com.dev.bitchord" ]]; then
      echo "FAIL: unexpected Wear OS applicationId '$OUT_PKG' in $apk" >&2
      exit 1
    fi
    WEAR_CERT="$OUT_CERT"
  elif [[ "$OUT_PKG" == *.tv ]]; then
    TV_CERT="$OUT_CERT"
  else
    PHONE_CERT="$OUT_CERT"
    outer_pkg="$OUT_PKG"
    outer_vcode="$OUT_VCODE"
    outer_cert="$OUT_CERT"

    # Check embedded Wear OS companion APK if bundled in phone APK assets
    wear_entry=""
    sha_entry=""
    for candidate_prefix in "assets/wear" "assets"; do
      if extract_zip_entry "$apk" "${candidate_prefix}/BitChordWear.apk" "$TMP_DIR/embedded-wear.apk" 2>/dev/null; then
        wear_entry="${candidate_prefix}/BitChordWear.apk"
        if extract_zip_entry "$apk" "${candidate_prefix}/BitChordWear.sha256" "$TMP_DIR/embedded-wear.sha256" 2>/dev/null; then
          sha_entry="${candidate_prefix}/BitChordWear.sha256"
        fi
        break
      fi
    done

    if [[ -n "$wear_entry" ]]; then
      if [[ -z "$sha_entry" ]]; then
        echo "FAIL: $apk embeds $wear_entry without matching BitChordWear.sha256" >&2
        exit 1
      fi
      recorded_wear_sha="$(normalize_sha256 "$(cat "$TMP_DIR/embedded-wear.sha256")")"
      actual_wear_sha="$(sha256_file "$TMP_DIR/embedded-wear.apk")"
      if [[ "$recorded_wear_sha" != "$actual_wear_sha" ]]; then
        echo "FAIL: $apk embedded $sha_entry ($recorded_wear_sha) != actual SHA-256 ($actual_wear_sha)" >&2
        exit 1
      fi

      inspect_apk "$TMP_DIR/embedded-wear.apk" "true"
      if [[ "$OUT_PKG" != "$outer_pkg" ]]; then
        echo "FAIL: embedded $wear_entry package ($OUT_PKG) != outer phone APK package ($outer_pkg)" >&2
        exit 1
      fi
      if [[ "$OUT_VCODE" != "$outer_vcode" ]]; then
        echo "FAIL: embedded $wear_entry versionCode ($OUT_VCODE) != outer phone APK versionCode ($outer_vcode)" >&2
        exit 1
      fi
      if [[ "$OUT_CERT" != "$outer_cert" ]]; then
        echo "FAIL: embedded $wear_entry cert SHA-256 ($OUT_CERT) != outer phone APK cert ($outer_cert)" >&2
        exit 1
      fi

      if [[ "$base_name" == "BitChord-v"*.apk && "$base_name" != *_* ]] || [[ "$base_name" == *universal* ]]; then
        EMBEDDED_WEAR_VERIFIED=1
        printf '  [OK]   └─ embedded %-28s file_sha256=%s cert_sha256=%s\n' \
          "$wear_entry" "$actual_wear_sha" "$OUT_CERT"
      fi
    elif [[ "$base_name" == "BitChord-v"*.apk && "$base_name" != *_* ]] || [[ "$base_name" == *universal* ]]; then
      echo "FAIL: universal phone APK $apk is missing embedded assets/wear/BitChordWear.apk" >&2
      exit 1
    fi
  fi
done

if [[ -n "$PHONE_CERT" && -n "$WEAR_CERT" ]]; then
  if [[ "$PHONE_CERT" != "$WEAR_CERT" ]]; then
    echo "FAIL: Phone ($PHONE_CERT) and Wear OS ($WEAR_CERT) APKs have different signing certificates; Wearable Data Layer pairing would fail!" >&2
    exit 1
  fi
  echo "Verified: Phone and Wear OS APKs share identical signing certificate ($PHONE_CERT)."
fi

if [[ -n "$PHONE_CERT" && -n "$TV_CERT" ]]; then
  if [[ "$PHONE_CERT" != "$TV_CERT" ]]; then
    echo "FAIL: Phone ($PHONE_CERT) and Android TV ($TV_CERT) APKs have different signing certificates!" >&2
    exit 1
  fi
  echo "Verified: Android TV APKs share the exact release signing certificate ($TV_CERT)."
fi

if [[ $EMBEDDED_WEAR_VERIFIED -eq 1 ]]; then
  echo "Verified: Embedded BitChordWear.apk matches .sha256 digest and outer Phone APK signature."
fi

# Optional upgrade verification against previous release APK(s)
if [[ ${#PREVIOUS_APKS[@]} -gt 0 ]]; then
  echo ""
  echo "=== Verifying Upgrade Compatibility Against Previous APK(s) ==="
  for prev_apk in "${PREVIOUS_APKS[@]}"; do
    [[ -f "$prev_apk" ]] || { echo "error: --previous-apk not found: $prev_apk" >&2; exit 1; }
    # Previous published APKs may predate v3 signing (v2-only); still require valid apksigner cert.
    inspect_apk "$prev_apk" "false"
    prev_pkg="$OUT_PKG"
    prev_vcode="$OUT_VCODE"
    prev_vname="$OUT_VNAME"
    prev_cert="$OUT_CERT"

    matched_indices=()
    if [[ -n "$TARGET_APK" ]]; then
      [[ -f "$TARGET_APK" ]] || { echo "error: --target-apk not found: $TARGET_APK" >&2; exit 1; }
      inspect_apk "$TARGET_APK" "true"
      matched_apks=("$TARGET_APK")
      matched_pkgs=("$OUT_PKG")
      matched_vcodes=("$OUT_VCODE")
      matched_vnames=("$OUT_VNAME")
      matched_certs=("$OUT_CERT")
    else
      matched_apks=()
      matched_pkgs=()
      matched_vcodes=()
      matched_vnames=()
      matched_certs=()
      for i in "${!APK_FILES[@]}"; do
        if [[ "${APK_PKGS[$i]}" == "$prev_pkg" ]]; then
          matched_apks+=("${APK_FILES[$i]}")
          matched_pkgs+=("${APK_PKGS[$i]}")
          matched_vcodes+=("${APK_VCODES[$i]}")
          matched_vnames+=("${APK_VNAMES[$i]}")
          matched_certs+=("${APK_CERTS[$i]}")
        fi
      done
    fi

    if [[ ${#matched_apks[@]} -eq 0 ]]; then
      echo "FAIL: no new APK found matching previous package '$prev_pkg' (from $prev_apk)" >&2
      exit 1
    fi

    live_candidate=""
    live_candidate_vcode=""
    for i in "${!matched_apks[@]}"; do
      new_apk="${matched_apks[$i]}"
      new_pkg="${matched_pkgs[$i]}"
      new_vcode="${matched_vcodes[$i]}"
      new_vname="${matched_vnames[$i]}"
      new_cert="${matched_certs[$i]}"

      if [[ "$new_pkg" != "$prev_pkg" ]]; then
        echo "FAIL: package mismatch: previous=$prev_pkg ($prev_apk) vs new=$new_pkg ($new_apk)" >&2
        exit 1
      fi
      if [[ "$new_cert" != "$prev_cert" ]]; then
        echo "FAIL: INSTALL_FAILED_UPDATE_INCOMPATIBLE — certificate mismatch for $new_pkg!" >&2
        echo "  previous ($prev_apk): $prev_cert" >&2
        echo "  new      ($new_apk): $new_cert" >&2
        exit 1
      fi
      if ! [[ "$new_vcode" =~ ^[0-9]+$ && "$prev_vcode" =~ ^[0-9]+$ ]] || (( new_vcode <= prev_vcode )); then
        echo "FAIL: versionCode must strictly increase for upgrade: previous=$prev_vcode ($prev_apk) -> new=$new_vcode ($new_apk)" >&2
        exit 1
      fi

      printf '  [OK] Upgrade %s: %s (vCode %s -> %s, vName %s -> %s, cert %s)\n' \
        "$new_pkg" "$(basename "$new_apk")" "$prev_vcode" "$new_vcode" "$prev_vname" "$new_vname" "$new_cert"

      base_new="$(basename "$new_apk")"
      if [[ -z "$live_candidate" ]] || [[ "$base_new" == *universal* ]] || [[ "$base_new" != *_* && "$base_new" != *Wear* ]]; then
        if [[ "$base_new" != *Wear* || $(basename "$prev_apk") == *Wear* ]]; then
          live_candidate="$new_apk"
          live_candidate_vcode="$new_vcode"
        fi
      fi
    done

    if [[ -n "$ADB_DEVICE" ]]; then
      ADB_BIN="$(discover_adb)" || {
        echo "error: --adb-device specified ($ADB_DEVICE), but 'adb' was not found" >&2
        exit 1
      }
      echo "  Running live adb install -r upgrade & data retention check on $ADB_DEVICE ($prev_pkg)..."
      "$ADB_BIN" -s "$ADB_DEVICE" wait-for-device

      # Start from a clean slate on the test device so old APK installs deterministically
      "$ADB_BIN" -s "$ADB_DEVICE" uninstall "$prev_pkg" >/dev/null 2>&1 || true
      "$ADB_BIN" -s "$ADB_DEVICE" install "$prev_apk"

      probe_token="bitchord-upgrade-probe-$(date +%s)-$$"
      probe_ext="/sdcard/Android/data/${prev_pkg}/files/.bitchord_upgrade_probe"
      "$ADB_BIN" -s "$ADB_DEVICE" shell "mkdir -p '/sdcard/Android/data/${prev_pkg}/files' && printf '%s' '$probe_token' > '$probe_ext'"

      pre_dump="$("$ADB_BIN" -s "$ADB_DEVICE" shell dumpsys package "$prev_pkg")"
      pre_uid="$(printf '%s\n' "$pre_dump" | tr -d '\r' | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | head -n1)"
      pre_install_time="$(printf '%s\n' "$pre_dump" | tr -d '\r' | sed -n 's/^[[:space:]]*firstInstallTime=\(.*\)$/\1/p' | head -n1)"

      # Perform in-place upgrade without uninstalling
      "$ADB_BIN" -s "$ADB_DEVICE" install -r "$live_candidate"

      post_dump="$("$ADB_BIN" -s "$ADB_DEVICE" shell dumpsys package "$prev_pkg")"
      post_uid="$(printf '%s\n' "$post_dump" | tr -d '\r' | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | head -n1)"
      post_vcode="$(printf '%s\n' "$post_dump" | tr -d '\r' | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n1)"
      post_install_time="$(printf '%s\n' "$post_dump" | tr -d '\r' | sed -n 's/^[[:space:]]*firstInstallTime=\(.*\)$/\1/p' | head -n1)"
      retained_token="$("$ADB_BIN" -s "$ADB_DEVICE" shell "cat '$probe_ext' 2>/dev/null || true" | tr -d '\r')"
      "$ADB_BIN" -s "$ADB_DEVICE" shell "rm -f '$probe_ext'" >/dev/null 2>&1 || true

      if [[ "$post_vcode" != "$live_candidate_vcode" ]]; then
        echo "FAIL: live upgrade versionCode on $ADB_DEVICE is '$post_vcode', expected '$live_candidate_vcode'" >&2
        exit 1
      fi
      if [[ -n "$pre_uid" && "$post_uid" != "$pre_uid" ]]; then
        echo "FAIL: app UID changed across upgrade ($pre_uid -> $post_uid)" >&2
        exit 1
      fi
      if [[ -n "$pre_install_time" && "$post_install_time" != "$pre_install_time" ]]; then
        echo "FAIL: firstInstallTime changed across upgrade ('$pre_install_time' -> '$post_install_time')" >&2
        exit 1
      fi
      if [[ "$retained_token" != "$probe_token" ]]; then
        echo "FAIL: seeded app data was NOT retained across adb install -r (expected '$probe_token', got '$retained_token')" >&2
        exit 1
      fi
      echo "  [OK] Live adb install -r succeeded on $ADB_DEVICE: UID=$post_uid, versionCode=$post_vcode, app data retained."
    fi
  done
fi

echo ""
echo "All Android signature and upgrade checks PASSED (cert SHA-256: $COMMON_CERT)."
