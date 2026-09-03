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
cp docker-compose.yml "$DEST_DIR/docker-compose.yml"

# Copy every shell script while preserving its path in the deployment folder.
# This keeps newly added runtime scripts from being omitted from future builds.
while IFS= read -r -d '' source; do
    destination="$DEST_DIR/$source"
    mkdir -p "$(dirname "$destination")"
    cp "$source" "$destination"
    chmod +x "$destination"
done < <(find . -maxdepth 2 -type f -name '*.sh' \
    ! -path './target/*' -print0)

# Copy the complete face-service build context, excluding generated Python
# caches. Preserve subdirectories in case models or configuration are added.
while IFS= read -r -d '' source; do
    destination="$DEST_DIR/$source"
    mkdir -p "$(dirname "$destination")"
    cp "$source" "$destination"
done < <(find face-service -type f \
    ! -path '*/__pycache__/*' ! -name '*.pyc' -print0)

echo "Deployed JAR to $DEST_JAR"
echo "Copied shell scripts and runtime infrastructure files to $DEST_DIR"
echo "Copied face-service build context to $DEST_DIR/face-service"
