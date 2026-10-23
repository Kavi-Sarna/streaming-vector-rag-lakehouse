#!/usr/bin/env bash
# Go lint and Buf workspace validation.
#
# Buf refuses to lint a module that contains no .proto files, so the workspace
# config is loaded every time and `buf lint` starts once a contract exists.
set -euo pipefail

cd "$(dirname "$0")/.."

command -v golangci-lint >/dev/null 2>&1 \
  || { echo "golangci-lint v2.15.0 is required (https://golangci-lint.run/docs/welcome/install/)" >&2; exit 1; }
command -v buf >/dev/null 2>&1 \
  || { echo "buf v1.75.0 is required (https://buf.build/docs/cli/installation/)" >&2; exit 1; }

( cd go && golangci-lint run ./... )
buf config ls-modules >/dev/null

if find api -name '*.proto' -print -quit | grep -q .; then
  buf lint
  buf format -d --exit-code
else
  echo "buf lint: api/ has no .proto files yet"
fi
