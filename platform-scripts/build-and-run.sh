#!/usr/bin/env bash
set -euo pipefail

REPO_PATH="$1"                # e.g. ../sample-task-api
IMAGE_NAME="showcase-sample:latest"
CONTAINER_NAME="showcase-sample"

echo "== Building image from $REPO_PATH =="
docker build -f Dockerfile.generic -t "$IMAGE_NAME" "$REPO_PATH"

echo "== Removing any previous container =="
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

echo "== Running container with resource limits =="
docker run -d \
  --name "$CONTAINER_NAME" \
  --memory="256m" \
  --cpus="0.5" \
  -p 8081:8080 \
  "$IMAGE_NAME"

echo "== Waiting for app to become healthy =="
for i in {1..15}; do
  if curl -sf http://localhost:8081/api/health > /dev/null; then
    echo "Health check passed."
    exit 0
  fi
  sleep 2
done

echo "Health check FAILED after 30s"
docker logs "$CONTAINER_NAME"
exit 1