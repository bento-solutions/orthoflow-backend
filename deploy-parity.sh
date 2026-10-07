#!/usr/bin/env bash
# OrthoFlow "parity" deploy: feature/denteam-parity, beside production.
# Run by hand on the VPS from the parity checkout (no CI deploys this branch):
#
#   cd /srv/bento/apps/orthoflow-parity && ./deploy-parity.sh
#
# Layout mirrors production (see docker-compose.parity.yml for what is kept
# apart): this checkout is the backend repo, with the frontend repo cloned into
# ./frontend. Unlike deploy.sh there is no GHCR image to pull, so both images are
# built here and tagged <backend-sha>-<frontend-sha>. The tag names the exact
# code a container runs, and both services get the same one, so a rebuild of one
# can never leave the other on a stale image (the trap deploy.sh pins around).
#
# Never touches production: a different compose project, containers, volumes,
# network, Traefik routers and image names. It does not prune images host-wide;
# old parity tags can be removed by hand (`docker image ls 'orthoflow-parity-*'`).
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE=docker-compose.parity.yml
PROJECT=orthoflow-parity

cd "$APP_DIR"

# Refuse to run from a checkout that is not the parity one: the project name is
# what keeps the volumes apart from production's.
if [ "$(basename "$APP_DIR")" != "$PROJECT" ]; then
  echo "ERROR: run this from a checkout named '$PROJECT' (this is '$(basename "$APP_DIR")')" >&2
  exit 2
fi
[ -f .env ] || { echo "ERROR: $APP_DIR/.env is missing (DB_PASSWORD and JWT_SECRET are required)" >&2; exit 2; }
[ -d frontend/.git ] || { echo "ERROR: ./frontend is not a clone of orthoflow-front" >&2; exit 2; }

dc() { docker compose -p "$PROJECT" -f "$COMPOSE_FILE" "$@"; }

# Both repos are PUBLIC, so fetches need no credentials. Same flags as deploy.sh:
# HTTP/1.1 dodges the HTTP/2 401 on anonymous upload-pack with git 2.43, and the
# cleared helper/extraheader ignore any stale cached token. Do NOT reuse this if
# a repo is ever made private.
git_pub() {
  GIT_TERMINAL_PROMPT=0 git \
    -c http.version=HTTP/1.1 \
    -c credential.helper= \
    -c 'http.https://github.com/.extraheader=' \
    "$@"
}

# --ff-only fails loudly rather than clobbering a hand edit on the box.
sync_repo() {
  local dir="$1" branch
  branch="$(git -C "$dir" rev-parse --abbrev-ref HEAD)"
  git_pub -C "$dir" fetch --quiet origin "$branch"
  git_pub -C "$dir" merge --ff-only --quiet "origin/$branch"
  echo "$dir: $branch @ $(git -C "$dir" rev-parse --short=12 HEAD)"
}
sync_repo .
sync_repo frontend

PARITY_TAG="$(git rev-parse --short=12 HEAD)-$(git -C frontend rev-parse --short=12 HEAD)"
export PARITY_TAG
echo "Building $PROJECT images, tag $PARITY_TAG"

# One at a time: Maven and the Angular build together would spike memory on a
# host that also serves production and the CRM.
dc build backend
dc build front

dc up -d

wait_healthy() {
  local service="$1" container status="" i
  container="$(dc ps -q "$service")"
  if [ -z "$container" ]; then
    echo "ERROR: could not resolve the '$service' container for project '$PROJECT'" >&2
    dc ps >&2
    exit 1
  fi
  echo "Waiting for $PROJECT/$service ($container) to become healthy..."
  for i in $(seq 1 120); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealthcheck{{end}}' "$container" 2>/dev/null || echo missing)"
    case "$status" in
      healthy|nohealthcheck) echo "$PROJECT/$service up ($status) after ${i}s"; return 0 ;;
      unhealthy)
        echo "ERROR: $PROJECT/$service reported unhealthy" >&2
        docker logs "$container" --tail 80 >&2
        exit 1
        ;;
    esac
    sleep 1
  done
  echo "ERROR: $PROJECT/$service did not become healthy within 120s (last status: ${status:-unknown})" >&2
  docker logs "$container" --tail 80 >&2
  exit 1
}
wait_healthy db
wait_healthy backend
wait_healthy front

echo "Parity is up on $PARITY_TAG"
