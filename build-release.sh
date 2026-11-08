#!/usr/bin/env bash
#
# Build a signed release APK, the same way Android Studio's
# "Generate Signed Bundle / APK" and this repo's CI do it:
#   ./gradlew :app:assembleFullRelease
#   -Pandroid.injected.signing.store.file=...
#   -Pandroid.injected.signing.store.password=...
#   -Pandroid.injected.signing.key.alias=...
#   -Pandroid.injected.signing.key.password=...
#
# Values can come from the environment or from a .env file at the repo root.
# Missing values are asked for interactively. Passwords are read without echo.
#
# Usage:
#   ./build-release.sh                 # full flavor
#   ./build-release.sh pumpcontrol     # another flavor
#   WITH_WEAR=1 ./build-release.sh     # also build the Wear APK
#
# .env variables (see .env.example):
#   KEYSTORE_FILE=/path/to/release.jks
#   KEYSTORE_PASSWORD=...
#   KEY_ALIAS=...
#   KEY_PASSWORD=...                  # optional, defaults to KEYSTORE_PASSWORD
#   FLAVOR=full                       # optional
#   WITH_WEAR=1                       # optional
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLEW="$REPO_ROOT/gradlew"
ENV_FILE="$REPO_ROOT/.env"

usage() {
    sed -n '3,26p' "$0" | sed 's/^# \{0,1\}//'
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

# Load simple KEY=VALUE lines from .env without executing the file.
# Existing environment variables win; this only fills what is unset/empty.
load_env_file() {
    local file="$1" line key value
    [[ -f "$file" ]] || return 0

    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line%$'\r'}"
        [[ "$line" =~ ^[[:space:]]*$ ]] && continue
        [[ "$line" =~ ^[[:space:]]*# ]] && continue
        line="${line#"${line%%[![:space:]]*}"}"

        if [[ "$line" =~ ^export[[:space:]]+ ]]; then
            line="${line#export}"
            line="${line#"${line%%[![:space:]]*}"}"
        fi

        [[ "$line" == *=* ]] || continue
        key="${line%%=*}"
        value="${line#*=}"
        key="${key%"${key##*[![:space:]]}"}"

        # Strip matching single or double quotes.
        if [[ "$value" =~ ^\".*\"$ && ${#value} -ge 2 ]]; then
            value="${value:1:${#value}-2}"
        elif [[ "$value" =~ ^\'.*\'$ && ${#value} -ge 2 ]]; then
            value="${value:1:${#value}-2}"
        fi

        if [[ -z "${!key:-}" ]]; then
            printf -v "$key" '%s' "$value"
            export "$key"
        fi
    done < "$file"
}

load_env_file "$ENV_FILE"

# Accept the common alternative names too, so an existing CI-style .env works.
KEYSTORE_FILE="${KEYSTORE_FILE:-${KEYSTORE_PATH:-${RELEASE_STORE_FILE:-}}}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-${RELEASE_STORE_PASSWORD:-}}"
KEY_ALIAS="${KEY_ALIAS:-${RELEASE_KEY_ALIAS:-}}"
KEY_PASSWORD="${KEY_PASSWORD:-${RELEASE_KEY_PASSWORD:-}}"

FLAVOR="${FLAVOR:-full}"
if [[ $# -gt 0 && "${1:-}" != -* ]]; then
    FLAVOR="$1"
    shift
fi

case "$FLAVOR" in
    full|pumpcontrol|aapsclient|aapsclient2|aapsclient3) ;;
    *)
        echo "Unknown flavor: $FLAVOR" >&2
        echo "Expected one of: full, pumpcontrol, aapsclient, aapsclient2, aapsclient3" >&2
        exit 2
        ;;
esac

prompt_required() {
    local var_name="$1" label="$2" value
    while [[ -z "${!var_name:-}" ]]; do
        read -r -p "$label: " value
        [[ -n "$value" ]] || continue
        printf -v "$var_name" '%s' "$value"
        export "$var_name"
    done
}

prompt_secret() {
    local var_name="$1" label="$2" value
    if [[ -n "${!var_name:-}" ]]; then
        return 0
    fi
    while [[ -z "${!var_name:-}" ]]; do
        read -r -s -p "$label: " value
        echo
        [[ -n "$value" ]] || continue
        printf -v "$var_name" '%s' "$value"
        export "$var_name"
    done
}

prompt_required KEYSTORE_FILE "Keystore file (.jks)"

# Expand a leading ~ and make a relative path relative to the repo root.
case "$KEYSTORE_FILE" in
    "~/"*) KEYSTORE_FILE="$HOME/${KEYSTORE_FILE#\~/}" ;;
esac
if [[ "$KEYSTORE_FILE" != /* ]]; then
    KEYSTORE_FILE="$REPO_ROOT/$KEYSTORE_FILE"
fi
if [[ ! -f "$KEYSTORE_FILE" ]]; then
    echo "Keystore not found: $KEYSTORE_FILE" >&2
    exit 2
fi

prompt_secret KEYSTORE_PASSWORD "Keystore password"
prompt_required KEY_ALIAS "Key alias"

if [[ -z "${KEY_PASSWORD:-}" ]]; then
    KEY_PASSWORD="$KEYSTORE_PASSWORD"
fi

if [[ ! -x "$GRADLEW" ]]; then
    echo "gradlew is not executable: $GRADLEW" >&2
    echo "Run: chmod +x gradlew" >&2
    exit 2
fi

FLAVOR_CAP="$(printf '%s' "${FLAVOR:0:1}" | tr '[:lower:]' '[:upper:]')${FLAVOR:1}"
TASKS=(":app:assemble${FLAVOR_CAP}Release")
if [[ "${WITH_WEAR:-0}" == "1" ]]; then
    TASKS+=(":wear:assemble${FLAVOR_CAP}Release")
fi

SIGNING_ARGS=(
    "-Pandroid.injected.signing.store.file=$KEYSTORE_FILE"
    "-Pandroid.injected.signing.store.password=$KEYSTORE_PASSWORD"
    "-Pandroid.injected.signing.key.alias=$KEY_ALIAS"
    "-Pandroid.injected.signing.key.password=$KEY_PASSWORD"
)

echo
echo "Building signed ${FLAVOR} release APK..."
echo "Keystore: $KEYSTORE_FILE"
echo "Alias:    $KEY_ALIAS"
echo

"$GRADLEW" "${TASKS[@]}" "${SIGNING_ARGS[@]}" --console=plain

APK_DIR="$REPO_ROOT/app/build/outputs/apk/$FLAVOR/release"
APK_FILE="$APK_DIR/app-$FLAVOR-release.apk"
if [[ ! -f "$APK_FILE" ]]; then
    # Fall back to the first APK in the output folder (name can differ per AGP version).
    APK_FILE="$(find "$APK_DIR" -maxdepth 1 -type f -name '*.apk' -print -quit 2>/dev/null || true)"
fi

if [[ -z "${APK_FILE:-}" || ! -f "$APK_FILE" ]]; then
    echo "Build finished, but no APK was found under: $APK_DIR" >&2
    exit 1
fi

echo
echo "APK: $APK_FILE"
if command -v sha256sum >/dev/null 2>&1; then
    echo "SHA-256: $(sha256sum "$APK_FILE" | awk '{print $1}')"
fi
