#!/usr/bin/env bash
# Runs Maven for the Flink job. Uses the host JDK when both java and mvn
# are installed, and a Maven image otherwise.
set -euo pipefail

cd "$(dirname "$0")/.."

if command -v java >/dev/null 2>&1 && command -v mvn >/dev/null 2>&1; then
  mvn -f flink/pom.xml -B "$@"
  exit 0
fi

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  if [[ "${REQUIRE_DOCKER:-0}" == "1" ]]; then
    echo "ERROR: the Flink tests need Java 17 and Maven, or Docker." >&2
    exit 1
  fi
  echo
  echo "WARNING: skipped Flink Maven — Java/Maven are not installed and Docker is not running."
  echo "         CI runs this step."
  echo
  exit 0
fi

repo="$(mkdir -p "${TMPDIR:-/tmp}/rag-flink-m2" && cd "${TMPDIR:-/tmp}/rag-flink-m2" && pwd)"
docker run --rm \
  --entrypoint mvn \
  -e HOME=/tmp \
  -v "$PWD":/workspace \
  -w /workspace \
  -v "$repo":/var/maven/.m2 \
  -u "$(id -u):$(id -g)" \
  maven:3.9.11-eclipse-temurin-17 \
  -f flink/pom.xml -B -Dmaven.repo.local=/var/maven/.m2 "$@"
