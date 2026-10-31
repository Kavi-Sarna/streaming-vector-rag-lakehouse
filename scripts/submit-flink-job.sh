#!/usr/bin/env bash
# Builds the dedup job and submits it to the local Flink cluster.
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose -f infra/compose/docker-compose.yml)

./scripts/flink-mvn.sh -DskipTests package

"${compose[@]}" exec -T redpanda rpk topic describe document.chunks.v1 -X brokers=127.0.0.1:9093 >/dev/null 2>&1 \
  || "${compose[@]}" exec -T redpanda rpk topic create document.chunks.v1 -p 1 -r 1 -X brokers=127.0.0.1:9093
"${compose[@]}" exec -T redpanda rpk topic describe document.embeddings.v1 -X brokers=127.0.0.1:9093 >/dev/null 2>&1 \
  || "${compose[@]}" exec -T redpanda rpk topic create document.embeddings.v1 -p 1 -r 1 -X brokers=127.0.0.1:9093

python3 - <<'PY'
import json
import urllib.request

overview = json.load(urllib.request.urlopen("http://127.0.0.1:8081/jobs/overview", timeout=5))
for job in overview.get("jobs") or []:
    if job.get("name") != "dedup-and-chunk":
        continue
    if job.get("state") not in {"RUNNING", "CREATED", "RESTARTING", "RECONCILING", "INITIALIZING"}:
        continue
    request = urllib.request.Request(
        f"http://127.0.0.1:8081/jobs/{job['jid']}?mode=cancel",
        method="PATCH",
    )
    with urllib.request.urlopen(request, timeout=10):
        pass
    print(f"cancelled {job['jid']}")
PY

"${compose[@]}" cp flink/target/dedup-job.jar flink-jobmanager:/tmp/dedup-job.jar
"${compose[@]}" exec -T flink-jobmanager flink run -d /tmp/dedup-job.jar \
  --brokers redpanda:9093 \
  --embed-url http://mock-embedder:8080/embed \
  --redis-host redis \
  --iceberg-warehouse s3://lakehouse/warehouse \
  --s3-endpoint http://minio:9000
echo "dedup-and-chunk submitted. Redis lines are in the taskmanager log. Iceberg commits on the next checkpoint."
