# Async embeddings

The local job still dedupes and chunks. Each kept chunk that has text is sent to the mock embedder. The call returns whenever the embedder finishes, so an older version can arrive after a newer one. The job drops that older vector.

```sh
make docker-up
make flink-job
make producer ARGS='--sleep-max=0 --burst=20 --seed=1'
```

`make flink-job` submits `dedup-and-chunk` with `--embed-url http://mock-embedder:8080/embed`. Chunks are still written to `document.chunks.v1`. Accepted vectors are raw Protobuf `DocumentEmbedding` records on `document.embeddings.v1`. A delete is one record with an empty vector and is not called an embedding.

```sh
docker compose -f infra/compose/docker-compose.yml logs flink-taskmanager | grep "Vector embedded successfully"
```

Three failures in a row open the embedder circuit for five seconds. The next failure after that cooldown opens it again. `make flink-job` uses the mock embedder. Titan is selected with `--embedder=bedrock`, as described in [docs/walkthroughs/06-bedrock-titan-embeddings.md](docs/walkthroughs/06-bedrock-titan-embeddings.md).
