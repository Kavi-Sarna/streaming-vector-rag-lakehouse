#!/usr/bin/env bash
# Checks the local stack from the host after `make docker-up`.
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose -f infra/compose/docker-compose.yml)

"${compose[@]}" exec -T redpanda rpk cluster health
"${compose[@]}" exec -T redpanda rpk topic describe document.events.v1 -X brokers=127.0.0.1:9093 >/dev/null
"${compose[@]}" exec -T redpanda rpk topic describe document.chunks.v1 -X brokers=127.0.0.1:9093 >/dev/null
python3 - <<'PY'
import socket
socket.create_connection(("127.0.0.1", 9092), 5).close()
print("ok: redpanda localhost:9092 and topics document.events.v1, document.chunks.v1")
PY

curl -fsS http://127.0.0.1:9000/minio/health/live >/dev/null
"${compose[@]}" exec -T minio mc ls local/lakehouse >/dev/null
echo "ok: minio bucket lakehouse"

python3 - <<'PY'
import socket
s = socket.create_connection(("127.0.0.1", 6379), 5)
s.sendall(b"PING\r\n")
data = s.recv(16)
s.close()
if not data.startswith(b"+PONG"):
    raise SystemExit(f"redis ping returned {data!r}")
print("ok: redis localhost:6379")
PY

python3 - <<'PY'
import json, time, urllib.request
def get(url):
    with urllib.request.urlopen(url, timeout=5) as response:
        return json.load(response)
for _ in range(30):
    managers = get("http://127.0.0.1:8081/taskmanagers").get("taskmanagers") or []
    if managers:
        print(f"ok: flink taskmanagers {len(managers)}")
        break
    time.sleep(2)
else:
    raise SystemExit("flink has no taskmanagers")
PY

python3 - <<'PY'
import json, urllib.request
request = urllib.request.Request(
    "http://127.0.0.1:8091/embed",
    data=json.dumps({"text": "hello"}).encode(),
    headers={"Content-Type": "application/json"},
)
with urllib.request.urlopen(request, timeout=5) as response:
    body = json.load(response)
vector = body.get("vector") or []
if body.get("dimensions") != 384 or len(vector) != 384:
    raise SystemExit(f"mock embedder returned {body.get('dimensions')} dimensions")
print("ok: mock embedder 384 dimensions")
PY

curl -fsS http://127.0.0.1:9090/-/ready >/dev/null
echo "ok: prometheus localhost:9090"

python3 - <<'PY'
import json, urllib.request
with urllib.request.urlopen("http://127.0.0.1:3000/api/health", timeout=5) as response:
    body = json.load(response)
if body.get("database") != "ok":
    raise SystemExit(f"grafana health {body}")
print("ok: grafana localhost:3000")
PY

echo "local stack is healthy"
