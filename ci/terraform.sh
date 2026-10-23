#!/usr/bin/env bash
# Format-check and validate the Terraform roots.
#
# This does not plan or apply. A plan needs AWS credentials and the state
# bucket from the bootstrap stack. CI sets REQUIRE_TERRAFORM=1 so a missing
# binary fails the job. Locally the step is skipped with a warning.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v terraform >/dev/null 2>&1; then
  if [[ "${REQUIRE_TERRAFORM:-0}" == "1" ]]; then
    echo "ERROR: REQUIRE_TERRAFORM=1 but terraform is not on PATH." >&2
    exit 1
  fi
  echo
  echo "WARNING: skipped Terraform checks — terraform is not installed."
  echo "         CI runs this step. Install Terraform 1.16.7 and re-run."
  echo
  exit 0
fi

terraform fmt -check -recursive infra/terraform

roots=(
  infra/terraform/bootstrap
  infra/terraform/environments/demo
)

for dir in "${roots[@]}"; do
  echo "==> terraform validate ${dir}"
  terraform -chdir="${dir}" init -backend=false -input=false
  terraform -chdir="${dir}" validate
done
