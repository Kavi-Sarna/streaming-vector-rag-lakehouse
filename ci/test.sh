#!/usr/bin/env bash
# Unit tests for the Go module.
set -euo pipefail

cd "$(dirname "$0")/.."

command -v go >/dev/null 2>&1 \
  || { echo "Go 1.27.3 is required (https://go.dev/dl/)" >&2; exit 1; }

( cd go && go test ./... )
