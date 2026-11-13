#!/usr/bin/env bash
# Package the already-built hidden tile APK as a Magisk-installable ZIP.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$ROOT_DIR/magisk"
APK_PATH="${1:-$ROOT_DIR/tile/build/outputs/apk/debug/tile-debug.apk}"
MODULE_PROP="$MODULE_DIR/module.prop"

if [[ ! -s "$APK_PATH" ]]; then
    echo "Tile APK was not found or is empty: $APK_PATH" >&2
    echo "Run ./gradlew :tile:assembleDebug first." >&2
    exit 1
fi

VERSION="$(sed -n 's/^version=//p' "$MODULE_PROP" | head -n 1)"
if [[ -z "$VERSION" || ! "$VERSION" =~ ^[0-9A-Za-z._-]+$ ]]; then
    echo "Invalid or missing version in $MODULE_PROP" >&2
    exit 1
fi

OUTPUT_DIR="$ROOT_DIR/build/magisk"
OUTPUT_PATH="$OUTPUT_DIR/FrontFlashQuickSettings-v${VERSION}.zip"
STAGING_DIR="$(mktemp -d)"
cleanup() {
    rm -rf "$STAGING_DIR"
}
trap cleanup EXIT

mkdir -p "$OUTPUT_DIR" "$STAGING_DIR/system/priv-app/FrontFlashQuickSettings"
cp -a "$MODULE_DIR/." "$STAGING_DIR/"
cp "$APK_PATH" "$STAGING_DIR/system/priv-app/FrontFlashQuickSettings/FrontFlashQuickSettings.apk"

# Android scans the application only when the package directory is searchable and the APK is
# readable. Magisk executes the boot hooks only when they are executable.
find "$STAGING_DIR" -type d -exec chmod 0755 {} +
find "$STAGING_DIR" -type f -exec chmod 0644 {} +
chmod 0755 "$STAGING_DIR/service.sh" "$STAGING_DIR/uninstall.sh"
chmod 0755 "$STAGING_DIR/META-INF/com/google/android/update-binary"

rm -f "$OUTPUT_PATH"
(
    cd "$STAGING_DIR"
    zip -X -q -r "$OUTPUT_PATH" .
)

if ! unzip -tq "$OUTPUT_PATH" >/dev/null; then
    echo "The generated module ZIP failed its integrity test." >&2
    exit 1
fi

for required_path in \
    module.prop \
    service.sh \
    META-INF/com/google/android/update-binary \
    system/priv-app/FrontFlashQuickSettings/FrontFlashQuickSettings.apk; do
    if ! unzip -Z1 "$OUTPUT_PATH" | grep -Fxq "$required_path"; then
        echo "The generated module ZIP is missing $required_path" >&2
        exit 1
    fi
done

echo "Built Magisk module: $OUTPUT_PATH"
