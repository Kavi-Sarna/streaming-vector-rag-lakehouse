# Flink dedup and chunking

Start the local stack, submit the job, then publish a short burst.

```sh
make docker-up
make flink-job
make producer ARGS='--sleep-max=0 --burst=20 --seed=1'
```

`make flink-job` builds `flink/target/dedup-job.jar` and submits it to the JobManager at `http://localhost:8081`. The job name is `dedup-and-chunk`. Submitting again cancels the previous run of that name first.

The job reads raw Protobuf `DocumentEvent` records from `document.events.v1` on `redpanda:9093`. It starts at the earliest offset, so records already in the topic are chunked too. It keys the stream by document id and stores the highest version it has applied. A second record with that same version is dropped. An insert and the following update use two versions, so both stay. A version lower than the stored one is dropped.

Each kept event is split on markdown headings. A fenced code block stays in the section that contains it, including a line inside the fence that starts with `#`. A list stays in that section too, including an indented continuation line. A delete becomes one chunk with empty text.

The chunks are raw Protobuf `DocumentChunk` records on `document.chunks.v1`, keyed by document id. The taskmanager log has one line per chunk:

```sh
docker compose -f infra/compose/docker-compose.yml logs flink-taskmanager | grep "Chunk emitted"
```

`make test` checks the version rule and the chunker. This job runs on the local Flink cluster. It does not change the EKS cluster.
