#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
MVN=${MVN:-mvn}

echo "Building modular Gallery App..."
"$MVN" clean verify

JAR=$(find gallery-app/target -maxdepth 1 -type f -name 'gallery-app-*.jar' ! -name '*.original' | head -1)
if [[ -z "${JAR:-}" || ! -f "$JAR" ]]; then
    echo "Build failed: executable JAR not found in gallery-app/target/" >&2
    exit 1
fi

echo "Build successful: $JAR"
if [[ "${INSTALL_LOCAL:-false}" == "true" ]]; then
    DEST_DIR="${DEST_DIR:-/home/jonathan/Gallery}"
    mkdir -p "$DEST_DIR"
    cp "$JAR" "$DEST_DIR/gallery.jar"
    echo "Copied to $DEST_DIR/gallery.jar"
fi
