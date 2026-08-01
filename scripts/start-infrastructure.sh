#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "Starting PostgreSQL/pgvector and face-service..."
docker compose up -d --build postgres face-service

echo "Waiting for PostgreSQL..."
for attempt in {1..60}; do
  status=$(docker inspect --format='{{.State.Health.Status}}' gallery-postgres 2>/dev/null || true)
  if [[ "$status" == "healthy" ]]; then
    echo "PostgreSQL is ready."
    break
  fi
  if [[ "$attempt" == "60" ]]; then
    echo "PostgreSQL did not become healthy. Run: docker compose logs postgres" >&2
    exit 1
  fi
  sleep 2
done

# The first DeepFace start can take longer while models are downloaded. Do not
# block Java startup indefinitely; the app can start and the service becomes
# available as soon as its health check succeeds.
face_status=$(docker inspect --format='{{.State.Health.Status}}' gallery-face-service 2>/dev/null || true)
echo "Face service status: ${face_status:-starting}"

if command -v curl >/dev/null 2>&1 && ! curl -fsS http://localhost:11434/api/tags >/dev/null 2>&1; then
  echo "WARNING: Ollama is not responding at http://localhost:11434."
  echo "Start Ollama and pull the models listed in README.md for AI functions."
fi
