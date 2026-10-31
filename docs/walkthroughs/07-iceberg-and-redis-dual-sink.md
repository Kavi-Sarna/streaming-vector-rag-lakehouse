# Iceberg and Redis

Accepted embeddings are written twice. The Kafka topic `document.embeddings.v1` stays. Iceberg stores the same rows as Parquet in the Silo bucket `lakehouse`, table `rag.document_embeddings`. A newer row for the same tenant, document, and chunk replaces the old one. A delete writes an equality tombstone for every chunk that version had stored. Redis Stack stores each chunk at `tenant:document:chunk`. The hash fields are `version`, `vector`, and `content`. A Lua script writes the hash when the incoming version is at least the version already stored, and it deletes the key on the same rule.

```sh
make docker-up
make flink-job
make producer ARGS='--sleep-max=0 --burst=20 --seed=1'
python3 scripts/verify-dual-sink.py --tenant=tenant-alpha
```

`make flink-job` still uses the mock embedder. The job creates the Iceberg table on `s3://lakehouse/warehouse` through Silo at `http://minio:9000` and writes Redis at `redis:6379`. The Flink containers place `flink-metrics-dropwizard` on the Flink lib path before the process starts, because Iceberg records writer metrics through that class. Iceberg commits when the checkpoint fires, every 10 seconds. The script waits up to 60 seconds, then checks that `tenant-alpha` has Redis hashes and that the Iceberg snapshot has records. It also sends an older version through the same Lua script and expects that write to be dropped.

```sh
docker compose -f infra/compose/docker-compose.yml logs flink-taskmanager | grep "Vector stored"
```

`make test` checks the version rule, the tombstone for a replaced chunk, and the Iceberg identity columns. This does not call Bedrock and does not start the demo cluster. S3 and the Glue Data Catalog are the next pull request.
