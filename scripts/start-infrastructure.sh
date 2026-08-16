#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "Starting face-service..."
docker compose up -d --build face-service


# The first DeepFace start can take longer while models are downloaded. Do not
# block Java startup indefinitely; the app can start and the service becomes
# available as soon as its health check succeeds.
face_status=$(docker inspect --format='{{.State.Health.Status}}' gallery-face-service 2>/dev/null || true)
echo "Face service status: ${face_status:-starting}"

if command -v curl >/dev/null 2>&1 && ! curl -fsS http://localhost:11434/api/tags >/dev/null 2>&1; then
  echo "WARNING: Ollama is not responding at http://localhost:11434."
  echo "Start Ollama and pull the models listed in README.md for AI functions."
fi
