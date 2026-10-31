#!/usr/bin/env python3
"""Check that tenant embeddings reached local Iceberg and Redis."""

import argparse
import json
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ["docker", "compose", "-f", str(ROOT / "infra/compose/docker-compose.yml")]
WAREHOUSE = "local/lakehouse/warehouse/rag/document_embeddings/metadata"


def redis_value(sock, parts):
    payload = f"*{len(parts)}\r\n".encode()
    for part in parts:
        if isinstance(part, str):
            part = part.encode()
        payload += f"${len(part)}\r\n".encode() + part + b"\r\n"
    sock.sendall(payload)
    return read_value(sock)


def read_line(sock):
    buf = b""
    while not buf.endswith(b"\r\n"):
        chunk = sock.recv(1)
        if not chunk:
            raise SystemExit("Redis closed the connection")
        buf += chunk
    return buf[:-2]


def read_value(sock):
    prefix = sock.recv(1)
    if prefix == b"+":
        return read_line(sock).decode()
    if prefix == b"-":
        raise SystemExit(read_line(sock).decode())
    if prefix == b":":
        return int(read_line(sock))
    if prefix == b"$":
        size = int(read_line(sock))
        if size < 0:
            return None
        data = b""
        while len(data) < size:
            chunk = sock.recv(size - len(data))
            if not chunk:
                raise SystemExit("Redis closed the connection")
            data += chunk
        trailer = b""
        while len(trailer) < 2:
            trailer += sock.recv(2 - len(trailer))
        return data
    if prefix == b"*":
        size = int(read_line(sock))
        if size < 0:
            return None
        return [read_value(sock) for _ in range(size)]
    raise SystemExit(f"unexpected Redis reply {prefix!r}")


def connect():
    try:
        sock = socket.create_connection(("127.0.0.1", 6379), 5)
    except OSError as exc:
        raise SystemExit("Redis is not accepting connections on localhost:6379. Run make docker-up.") from exc
    sock.settimeout(5)
    return sock


def scan_keys(sock, pattern):
    cursor = b"0"
    found = []
    while True:
        cursor, batch = redis_value(sock, [b"SCAN", cursor, b"MATCH", pattern.encode(), b"COUNT", b"200"])
        found.extend(key.decode() for key in batch)
        if cursor == b"0":
            return found


def hget(sock, key, field):
    value = redis_value(sock, ["HGET", key, field])
    return None if value is None else value.decode()


def stale_write_rejected(sock, key, version):
    script = (
        "local current_ver = redis.call('HGET', KEYS[1], 'version')\n"
        "if not current_ver or tonumber(ARGV[1]) >= tonumber(current_ver) then\n"
        "    redis.call('HSET', KEYS[1], 'version', ARGV[1], 'vector', ARGV[2], 'content', ARGV[3])\n"
        "    return 1\n"
        "end\n"
        "return 0\n"
    )
    older = str(int(version) - 1)
    result = redis_value(sock, ["EVAL", script, "1", key, older, "0", "stale"])
    return result == 0


def compose(*args):
    completed = subprocess.run(
        [*COMPOSE, "exec", "-T", "minio", *args],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        return ""
    return completed.stdout


def iceberg_records():
    compose(
        "mc",
        "alias",
        "set",
        "local",
        "http://127.0.0.1:9000",
        "minio",
        "minio12345",
    )
    listing = compose("mc", "find", WAREHOUSE, "--name", "*.metadata.json")
    best = None
    best_updated = -1
    for line in listing.splitlines():
        path = line.strip()
        if not path.endswith(".metadata.json"):
            continue
        raw = compose("mc", "cat", path)
        if not raw:
            continue
        metadata = json.loads(raw)
        updated = int(metadata.get("last-updated-ms") or 0)
        if updated >= best_updated:
            best = metadata
            best_updated = updated
    if best is None:
        return 0
    current = best.get("current-snapshot-id")
    if current in (None, -1):
        return 0
    for snapshot in best.get("snapshots") or []:
        if snapshot.get("snapshot-id") == current:
            summary = snapshot.get("summary") or {}
            return int(summary.get("total-records") or 0)
    return 0


def main():
    parser = argparse.ArgumentParser(description="Check Iceberg and Redis for one tenant.")
    parser.add_argument("--tenant", required=True)
    parser.add_argument("--timeout", type=int, default=60)
    args = parser.parse_args()

    deadline = time.time() + args.timeout
    keys = []
    records = 0
    print(f"waiting for {args.tenant} in Redis and Iceberg")
    while time.time() < deadline:
        with connect() as sock:
            keys = scan_keys(sock, f"{args.tenant}:*")
        records = iceberg_records()
        if keys and records > 0:
            break
        time.sleep(5)
    if not keys:
        raise SystemExit(
            f"no Redis keys for {args.tenant}. Run make flink-job and make producer ARGS='--sleep-max=0 --burst=20 --seed=1'."
        )
    if records <= 0:
        raise SystemExit(
            "Iceberg has no committed records in rag.document_embeddings. The job commits on a checkpoint."
        )

    with connect() as sock:
        version = None
        gated = None
        for key in keys:
            version = hget(sock, key, "version")
            if version is None:
                continue
            gated = key
            if stale_write_rejected(sock, key, version):
                break
            gated = None
        if gated is None or version is None:
            raise SystemExit(f"Redis accepted a stale version for {args.tenant}")

    print(f"ok: redis {args.tenant} keys={len(keys)} version gate kept {version} on {gated}")
    print(f"ok: iceberg rag.document_embeddings total-records={records}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
