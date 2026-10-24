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

`make seed-data-keps`, `make seed-data-arxiv`, and `make seed-data-hf` refresh one folder. The HuggingFace export needs `pip install -r scripts/requirements-seed.txt`. The downloaded files stay on the machine. They are not committed.

`make producer` publishes 300 events to `document.events.v1` on `localhost:9092`. It reads every markdown file under `testdata/`. Records are raw Protobuf. The key is the file path relative to `testdata`, such as `keps/sig-node_2000-graceful-node-shutdown`. The pause between records is random, from 200ms to 1.5s. Files are published in path order, so the run starts with `arxiv/`, then `hf/`, then `keps/`. Each file is inserted, then updated. After the update, the file is deleted about one time in five. About one record in five is published again with the same document id and version. That repeat is what the later dedupe step drops. An update is a new version, so it is kept. A later event inserts that file again when the burst still has room. Tenants alternate between `tenant-alpha` and `tenant-beta`, unless the file's front matter names a tenant. arXiv and HuggingFace files name `tenant-alpha`. The command prints the seed it used.

```sh
make producer ARGS='--sleep-min=100ms --sleep-max=400ms --burst=30 --delete-chance=0.3'
make producer ARGS='--seed=1 --burst=30'
make producer ARGS='--sleep-max=0 --rate=50 --burst=100'
```

The producer listens on `http://localhost:9095/metrics` for the whole run. Prometheus, started by `make docker-up`, scrapes that address every 2 seconds. Open `http://localhost:3000` for the Producer dashboard: insert, update, and delete totals, the publish rate, and the cumulative counts. After the burst finishes, the process keeps serving metrics until Ctrl+C, and the dashboard holds the final totals. `--metrics-addr=` turns the endpoint off and lets the process exit when the burst finishes.

The cloud form is a container image and a Kubernetes Job. The image downloads the three corpora during `docker build` and stores them at `/corpus`. A build machine does not need local markdown files.

```sh
make docker-build-producer
```

That builds `rag-lakehouse-producer:local`. The container uses the same defaults as `make producer`: 300 events, a random pause from 200ms to 1.5s, a delete about one time in five, and a duplicate replay about one time in five. It serves `rag_producer_events_total` on port 9095 and stays up after the burst, the same way the local process does.

`infra/k8s/` is that producer plus Prometheus and Grafana in namespace `rag`. `.github/workflows/network.yml` plans and applies the stack, including MSK Serverless and the Grafana load balancer. Unblock deploy waits on the same `demo` environment as Apply. Approving Apply does not approve the release. When the plan has no changes, that release approval is the only gate. When the plan has changes, it also waits until Apply has succeeded. Approving it runs `.github/workflows/deploy.yml`, which builds the linux/amd64 image, pushes `rag-demo-producer:<commit>` to ECR, writes the Glue schema version and the MSK bootstrap brokers into the Job, and runs `kubectl apply -k infra/k8s`. The producer service account assumes `rag-demo-producer`. The first publish creates `document.events.v1` if it is missing.

Approve both `demo` reviews when you want to watch the cloud producer. Run `make tf-bootstrap` first so the GitHub role can create the Grafana load balancer. After Apply and the deploy release succeed, run:

```sh
make kubeconfig
make open-grafana
```

`make kubeconfig` grants the IAM user in your current AWS session cluster admin and writes `~/.kube/config`. The cluster API uses IAM access entries, and this command creates that entry before it writes the kubeconfig.

That allows this machine's public IPv4 address and prints the dashboard URL. Grafana opens in the browser with no login. The load balancer accepts only that address. It can take a minute after the deploy job for the load balancer to see Grafana. Run `make open-grafana` again if your address changes. `make close-grafana` removes the rule.

```sh
kubectl -n rag port-forward svc/grafana 3000:3000
```

That still opens the same dashboard at `http://localhost:3000`. The Producer dashboard shows insert, update, delete, and the replayed copies. `kubectl delete job producer -n rag` stops the producer. `make tf-network-down` deletes the load balancer and MSK with the rest of the stack. Leave both reviews unapproved when you want the cluster to stay down.
