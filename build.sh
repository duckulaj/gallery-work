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
DEST_DIR="${DEST_DIR:-/home/jonathan/Gallery}"
DEST_JAR="$DEST_DIR/GalleryApp.jar"

mkdir -p "$DEST_DIR"
cp "$JAR" "$DEST_JAR"
cp run-gallery.sh "$DEST_DIR/run-gallery.sh"
mkdir -p "$DEST_DIR/scripts" "$DEST_DIR/face-service"
cp scripts/start-infrastructure.sh "$DEST_DIR/scripts/start-infrastructure.sh"
cp docker-compose.yml "$DEST_DIR/docker-compose.yml"
cp face-service/Dockerfile face-service/main.py face-service/requirements.txt "$DEST_DIR/face-service/"
chmod +x "$DEST_DIR/run-gallery.sh"
chmod +x "$DEST_DIR/scripts/start-infrastructure.sh"

echo "Deployed JAR to $DEST_JAR"
echo "Copied launcher to $DEST_DIR/run-gallery.sh"
echo "Copied runtime infrastructure files to $DEST_DIR"
