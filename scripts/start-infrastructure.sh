#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "Starting infrastructure..."
postgres_host="${POSTGRES_HOST:-localhost}"
postgres_port="${POSTGRES_PORT:-5432}"
postgres_user="${GALLERY_DB_USERNAME:-${POSTGRES_USER:-gallery_user}}"
postgres_db="${GALLERY_DB_NAME:-${POSTGRES_DB:-gallery_ai}}"

if command -v systemctl >/dev/null 2>&1 && ! systemctl is-active --quiet postgresql; then
  echo "ERROR: systemd PostgreSQL is not active. Start it with: sudo systemctl start postgresql" >&2
  exit 1
fi

if docker inspect gallery-face-service >/dev/null 2>&1; then
  face_state=$(docker inspect --format='{{.State.Status}}' gallery-face-service 2>/dev/null || true)
  if [[ "$face_state" != "running" ]]; then
    docker start gallery-face-service >/dev/null
  fi
else
  docker compose up -d --build face-service
fi

if command -v pg_isready >/dev/null 2>&1; then
  for attempt in {1..30}; do
    if pg_isready -h "$postgres_host" -p "$postgres_port" -U "$postgres_user" -d "$postgres_db" >/dev/null 2>&1; then
      break
    fi

    if (( attempt == 30 )); then
      echo "ERROR: PostgreSQL is not ready at $postgres_host:$postgres_port." >&2
      exit 1
    fi

    sleep 2
  done
else
  echo "WARNING: pg_isready is not installed; skipping PostgreSQL readiness check."
fi


# The first DeepFace start can take longer while models are downloaded. Do not
# block Java startup indefinitely; the app can start and the service becomes
# available as soon as its health check succeeds.
face_status=$(docker inspect --format='{{.State.Health.Status}}' gallery-face-service 2>/dev/null || true)
echo "PostgreSQL status: ready at $postgres_host:$postgres_port"
echo "Face service status: ${face_status:-starting}"

if command -v curl >/dev/null 2>&1 && ! curl -fsS http://localhost:11434/api/tags >/dev/null 2>&1; then
  echo "WARNING: Ollama is not responding at http://localhost:11434."
  echo "Start Ollama and pull the models listed in README.md for AI functions."
fi
