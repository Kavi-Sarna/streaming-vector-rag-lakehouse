#!/usr/bin/env bash
# Download the KEPs, arXiv, and HuggingFace corpora into testdata/.
# The producer image runs this during docker build, so a runner does not
# need markdown files on disk before the build.
set -euo pipefail

cd "$(dirname "$0")/.."

./scripts/download-seed-docs.sh

mkdir -p testdata/arxiv testdata/hf
curl -fsSL "https://export.arxiv.org/api/query?search_query=cat:cs.CL+OR+cat:cs.DC&start=0&max_results=40" -o testdata/arxiv/feed.xml
python3 scripts/fetch-arxiv-seed.py --input testdata/arxiv/feed.xml --output-dir testdata/arxiv
python3 -W ignore::UserWarning scripts/export-hf-seed.py --dataset ag_news --output-dir testdata/hf
