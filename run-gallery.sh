#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [[ -x ./scripts/start-infrastructure.sh ]]; then
	./scripts/start-infrastructure.sh
fi

if [[ -f ./GalleryApp.jar ]]; then
	exec java ${JAVA_OPTS:-} -jar ./GalleryApp.jar "$@"
fi

exec mvn -pl gallery-app -am spring-boot:run "$@"
