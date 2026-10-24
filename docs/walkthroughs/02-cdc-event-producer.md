# CDC event producer

Seed a markdown corpus, then publish `DocumentEvent` records to local Redpanda. Start the stack first with `make docker-up`.

```sh
make seed-data
make producer
```

`make seed-data` fills three folders under `testdata/`:

| Folder    | Source                                                                    | Files   |
| --------- | ------------------------------------------------------------------------- | ------- |
| `keps/`   | Kubernetes enhancement proposals from `sig-node` and `sig-architecture`   | 40      |
| `arxiv/`  | Abstracts from arXiv categories `cs.CL` and `cs.DC`                       | 40      |
| `hf/`     | Articles from the HuggingFace `ag_news` training split                    | 40      |

`make seed-data-keps`, `make seed-data-arxiv`, and `make seed-data-hf` refresh one folder. The HuggingFace export needs `pip install datasets`. The downloaded files stay on the machine. They are not committed.

`make producer` publishes 300 events to `document.events.v1` on `localhost:9092`. It reads every markdown file under `testdata/`. Records are raw Protobuf. The key is the file path relative to `testdata`, such as `keps/sig-node_2000-graceful-node-shutdown`. The pause between records is random, from 200ms to 1.5s. Files are published in path order, so the run starts with `arxiv/`, then `hf/`, then `keps/`. Each file is inserted, then updated. After the update, the file is deleted about one time in five. A later event inserts that file again when the burst still has room. Tenants alternate between `tenant-alpha` and `tenant-beta`, unless the file's front matter names a tenant. arXiv and HuggingFace files name `tenant-alpha`. The command prints the seed it used.

```sh
make producer ARGS='--sleep-min=100ms --sleep-max=400ms --burst=30 --delete-chance=0.3'
make producer ARGS='--seed=1 --burst=30'
make producer ARGS='--sleep-max=0 --rate=50 --burst=100'
```

The producer listens on `http://localhost:9095/metrics` for the whole run. Prometheus, started by `make docker-up`, scrapes that address every 2 seconds. Open `http://localhost:3000` for the Producer dashboard: insert, update, and delete totals, the publish rate, and the cumulative counts. After the burst finishes, the process keeps serving metrics until Ctrl+C, and the dashboard holds the final totals. `--metrics-addr=` turns the endpoint off and lets the process exit when the burst finishes.

A cloud run uses MSK Serverless and the Glue frame. The schema version id is the one `aws glue list-schema-versions` prints for `document_event`. The cluster has to be up, and the broker string is the MSK bootstrap servers on port 9098.

```sh
cd go && go run ./cmd/producer \
  --brokers="$MSK_BOOTSTRAP" \
  --security=aws-msk-iam \
  --glue-schema-version="$SCHEMA_VERSION_ID" \
  --input=../testdata \
  --sleep-max=0 \
  --rate=20 \
  --burst=50
```
