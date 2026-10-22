#!/usr/bin/env bash
# The CI contract. This is what "green" means, locally and in CI.
#
# Every provider — GitHub Actions, Buildkite — should call this script and
# nothing else. A check that exists only in a provider's YAML cannot be run
# on a laptop, and failures turn into commit-push-wait cycles.
set -euo pipefail

cd "$(dirname "$0")/.."

./ci/lint.sh
./ci/test.sh
./ci/smoke.sh
