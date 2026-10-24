#!/usr/bin/env bash
# Compose smoke. Starts the local stack, checks it from the host, then stops it.
#
# CI sets REQUIRE_DOCKER=1 so a missing daemon fails the job. Locally the step
# is skipped with a warning when Docker is not running, so `make ci` still
# checks lint and tests on a machine without a daemon.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  if [[ "${REQUIRE_DOCKER:-0}" == "1" ]]; then
    echo "ERROR: REQUIRE_DOCKER=1 but no Docker daemon is reachable." >&2
    exit 1
  fi
  echo
  echo "WARNING: skipped Compose smoke — no Docker daemon reachable."
  echo "         CI runs this step. Start Docker and re-run to check it here."
  echo
  exit 0
fi

cleanup() { make docker-down >/dev/null 2>&1 || true; }
trap cleanup EXIT

make docker-up
./scripts/verify-infra-health.sh
