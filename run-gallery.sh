#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./scripts/start-infrastructure.sh
exec mvn -pl gallery-app -am spring-boot:run
