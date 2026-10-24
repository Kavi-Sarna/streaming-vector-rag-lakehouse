#!/usr/bin/env bash
# Download Kubernetes enhancement proposals into testdata/keps.
set -euo pipefail

cd "$(dirname "$0")/.."
target="testdata/keps"
mkdir -p "$target"
find "$target" -name '*.md' -delete

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "cloning kubernetes/enhancements (sig-node and sig-architecture)"
git clone --depth 1 --filter=blob:none --sparse https://github.com/kubernetes/enhancements.git "$tmp/enhancements"
git -C "$tmp/enhancements" sparse-checkout set keps/sig-node keps/sig-architecture

count=0
while IFS= read -r file; do
  parent=$(basename "$(dirname "$file")")
  sig=$(basename "$(dirname "$(dirname "$file")")")
  cp "$file" "$target/${sig}_${parent}.md"
  count=$((count + 1))
  if [ "$count" -ge 40 ]; then
    break
  fi
done < <(find "$tmp/enhancements/keps" -type f -path '*/[0-9]*/README.md' | sort)

echo "wrote $count KEP documents to $target"
