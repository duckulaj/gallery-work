#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# Keep the command-line launcher aligned with the development credentials used
# by the VS Code launch profile. Callers can still override every value through
# the environment.
export GALLERY_DB_URL="${GALLERY_DB_URL:-jdbc:postgresql://localhost:5432/gallery_ai}"
export GALLERY_DB_USERNAME="${GALLERY_DB_USERNAME:-gallery_user}"
export GALLERY_DB_PASSWORD="${GALLERY_DB_PASSWORD:-ja9juja9ju}"

if [[ -x ./scripts/start-infrastructure.sh ]]; then
	./scripts/start-infrastructure.sh
fi

if [[ -f ./GalleryApp.jar ]]; then
	exec java ${JAVA_OPTS:-} -jar ./GalleryApp.jar "$@"
fi

exec mvn -pl gallery-app -am spring-boot:run "$@"
