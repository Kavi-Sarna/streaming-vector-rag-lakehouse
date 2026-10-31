# streaming-vector-rag-lakehouse

Event-driven streaming RAG platform and transactional lakehouse catalog. CDC events for technical markdown are chunked and embedded in a streaming job, stored in Apache Iceberg and a Redis vector index, and served through a streaming gRPC query API.

Local development uses Go, Apache Flink, Redpanda, Apache Iceberg, Redis Stack, MinIO, and Protobuf. An ephemeral AWS deployment uses EKS, MSK Serverless, S3, Glue, Bedrock, and a Network Load Balancer. The runtime map is in [docs/architecture/01-system-overview.md](docs/architecture/01-system-overview.md).

## Prerequisites

- Go 1.27.3
- golangci-lint 2.15.0
- Buf CLI 1.75.0
- Docker Compose v2
- Terraform 1.16.7
- GNU make
- Java 17 and Maven, or Docker. The Flink tests use Docker when Maven is not installed.
- Helm 4.3.1, for `make flink-operator`

## Layout

```
api/     Protobuf contracts.
go/      Go module. Generated Go stubs live in go/gen/v1.
flink/   Flink job. Generated Java stubs live in flink/src/main/java. `make flink-job` submits it.
infra/   Compose for the local stack, Terraform for the state bucket and VPC, Kubernetes manifests, and the Flink operator Helm chart.
scripts/ Health checks for that local stack.
ci/      The checks GitHub Actions runs. `make ci` calls the same scripts.
docs/    Architecture and walkthroughs.
```

## Checks

```sh
make ci
```

On every pull request and every push to `main`, GitHub Actions installs Go, golangci-lint, Buf, and Terraform, then runs `ci/run.sh`. That script is the whole job:

- `make lint` runs golangci-lint and checks the Buf workspace.
- `make test` runs the Go unit tests and the Flink unit tests.
- `make terraform` checks formatting and runs `terraform validate`. It does not call AWS.
- `make smoke` starts the local stack, checks it, and removes it.

A failure reproduces locally with `make ci`. In Actions the Compose step fails if Docker is missing. On a laptop without Docker it is skipped with a warning.

## Contracts

`api/proto/v1/document_event.proto` is one CDC change for a markdown document: tenant, document id, version, and insert, update, or delete. `api/proto/v1/document_chunk.proto` is one structural piece of that event. `api/proto/v1/document_embedding.proto` is that chunk plus the accepted vector. `api/proto/v1/rag_service.proto` is `v1.RAGService/StreamQuery`.

```sh
make proto-gen
make test
```

`make proto-gen` formats the contracts and writes Go stubs to `go/gen/v1` and Java stubs to `flink/src/main/java`. `make test` compiles the Go stubs and checks the Glue wire frame. `make lint` checks Buf lint and format.

Local records are the raw Protobuf bytes. A cloud record puts an 18-byte Glue header in front of those bytes: version `0x03`, a compression byte, and the schema version id. The demo stack registers `document_event` in the Glue registry `rag-demo`.

## Local stack

`make docker-up` starts the backing services and leaves them running. `make docker-health` checks them from the host. `make docker-down` stops them. `make flink-job` submits the dedup job to that Flink cluster. The query gateway is not submitted.

## CDC producer

`make seed-data` downloads three corpora into `testdata/`: 40 Kubernetes enhancement proposals in `keps/`, 40 arXiv abstracts in `arxiv/`, and 40 HuggingFace `ag_news` articles in `hf/`. The HuggingFace export needs `pip install -r scripts/requirements-seed.txt`. `make seed-data-keps`, `make seed-data-arxiv`, and `make seed-data-hf` refresh one source.

`make producer` publishes 300 `DocumentEvent` records to `document.events.v1` on `localhost:9092`. Redpanda must already be running. It reads every markdown file under `testdata/`. Each record is raw Protobuf, keyed by the path of the file, such as `keps/sig-node_2000-graceful-node-shutdown`. The pause between records is random, from 200ms to 1.5s, so the run lasts about three to seven minutes. Each file is inserted, then updated, and then deleted about one time in five. About one record in five is published again with the same version. `make flink-job` drops that repeat. An insert and the later update are different versions, so both stay. A version older than the one already applied is dropped too. Which files are deleted changes every run. The command prints the seed so the same choices can be repeated with `--seed`. Pass `ARGS='--sleep-min=100ms --sleep-max=400ms --burst=30 --delete-chance=0.3'` to change the pace and the delete share. `--sleep-max=0 --rate=50` publishes at a fixed 50 per second. `--security=aws-msk-iam` and `--glue-schema-version` are the MSK path.

While it publishes, counts are served at `http://localhost:9095/metrics`. Grafana at `http://localhost:3000` shows insert, update, delete, and the publish rate. After the burst, the process keeps serving those counts until Ctrl+C.

`make docker-build-producer` builds `rag-lakehouse-producer:local`. The image downloads the three corpora while it builds, so the machine does not need markdown files first. On a pull request, `.github/workflows/deploy.yml` runs after you approve the `demo` environment for the release. It builds the image, pushes it to ECR, and applies `infra/k8s/` on the cluster. A plan with changes also waits until Apply has succeeded. The steps are in [docs/walkthroughs/02-cdc-event-producer.md](docs/walkthroughs/02-cdc-event-producer.md).

| Service        | Address                              | What it is                                                                        |
| -------------- | ------------------------------------ | --------------------------------------------------------------------------------- |
| Redpanda       | `localhost:9092`                     | Kafka for the host. Flink uses `redpanda:9093` on the Compose network. Topics `document.events.v1`, `document.chunks.v1`, and `document.embeddings.v1`. |
| Silo           | `localhost:9000`, console `:9001`    | MinIO S3 API. User `minio`, password `minio12345`. Bucket `lakehouse`.            |
| Redis Stack    | `localhost:6379`                     | Chunk hashes at `tenant:document:chunk`. No password.                            |
| Flink          | `http://localhost:8081`              | JobManager and one TaskManager. `make flink-job` submits `dedup-and-chunk`.     |
| Mock embedder  | `http://localhost:8091/embed`        | `POST {"text":"..."}` returns a deterministic 384-dimension vector.               |
| Prometheus     | `http://localhost:9090`              | Scrapes the producer on the host at port 9095 every 2 seconds.                    |
| Grafana        | `http://localhost:3000`              | Producer dashboard. Opens without a login.                                        |

The object store image is `pgsty/silo`. It speaks the MinIO S3 API. The official MinIO image is no longer anonymously pullable.

## Flink dedup and chunking

`make flink-job` builds the job and submits `dedup-and-chunk` to the local JobManager. The job reads raw Protobuf from `document.events.v1`, keys by document id, and stores the highest version it has applied. It writes raw Protobuf `DocumentChunk` records to `document.chunks.v1`. Each kept event is split on markdown headings. A fenced code block stays together, and so does a list. A delete is one chunk with empty text. The steps are in [docs/walkthroughs/03-flink-dedup-and-chunking.md](docs/walkthroughs/03-flink-dedup-and-chunking.md).

```sh
make docker-up
make flink-job
docker compose -f infra/compose/docker-compose.yml logs flink-taskmanager | grep "Chunk emitted"
```

## Async embeddings

The same local job embeds each kept chunk that has text. The mock embedder at `http://mock-embedder:8080/embed` returns a 384-dimension vector. Calls finish out of order, and a vector for an older version of that document is dropped. Accepted records are raw Protobuf on `document.embeddings.v1`. A delete is stored with an empty vector. The steps are in [docs/walkthroughs/05-async-embedding.md](docs/walkthroughs/05-async-embedding.md).

```sh
make flink-job
docker compose -f infra/compose/docker-compose.yml logs flink-taskmanager | grep "Vector embedded successfully"
```

## Bedrock Titan embeddings

The same job can call Titan Text Embeddings V2 instead of the mock. The request asks for 1024 dimensions with normalization on. The model id is `amazon.titan-embed-text-v2:0`. `make flink-job` stays on the mock embedder, so a local run does not call Bedrock. A task with AWS credentials selects Titan with `--embedder=bedrock`. The steps are in [docs/walkthroughs/06-bedrock-titan-embeddings.md](docs/walkthroughs/06-bedrock-titan-embeddings.md).

## Iceberg and Redis

Accepted embeddings are written to Iceberg and Redis Stack as well as `document.embeddings.v1`. The Iceberg table is `rag.document_embeddings` in the Silo bucket `lakehouse`. A newer row for the same tenant, document, and chunk replaces the old one, and a delete writes an equality tombstone. Redis stores each chunk at `tenant:document:chunk`. A Lua script writes that hash when the incoming version is at least the version already stored. The steps are in [docs/walkthroughs/07-iceberg-and-redis-dual-sink.md](docs/walkthroughs/07-iceberg-and-redis-dual-sink.md).

```sh
python3 scripts/verify-dual-sink.py --tenant=tenant-alpha
```

## Flink Kubernetes Operator

The operator runs on the EKS cluster, in namespace `flink-operator`. The chart is `infra/helm/flink-operator`. cert-manager is a pre-install hook in that chart: the namespace is weight `-20` and the other cert-manager resources are weight `-10`, so they are ready before the operator chart creates its webhook certificate. cert-manager stays in namespace `cert-manager`. `.github/workflows/deploy.yml` installs the chart after the cluster exists. From the laptop, `make kubeconfig` and then `make flink-operator` do the same install. The steps are in [docs/walkthroughs/04-flink-kubernetes-operator.md](docs/walkthroughs/04-flink-kubernetes-operator.md).

```sh
make kubeconfig
make flink-operator
kubectl get pods -n flink-operator
```

`make flink-job` still submits `dedup-and-chunk` to the local Compose cluster. The operator does not run that job yet.

## AWS network

`ci` never calls AWS. `network` plans and applies the demo stack, and only after the one-time bootstrap below exists. `deploy` pushes images and applies Kubernetes manifests. When the plan has no changes, Unblock deploy is its own approval. When the plan has changes, that approval waits until Apply has succeeded.

|                           | What runs                                                                        | Creates resources                         |
| ------------------------- | -------------------------------------------------------------------------------- | ----------------------------------------- |
| GitHub `ci`               | lint, unit tests, `terraform validate`, Compose smoke                            | No                                        |
| GitHub `network`          | on a pull request: plan the VPC, Glue registry, producer image repository, EKS cluster, MSK Serverless, and the Grafana load balancer, then print the plan | Not yet |
| GitHub `network` apply    | only when that plan has changes, after you approve the `demo` environment        | Yes. VPC, one NAT gateway, the Glue registry, the producer image repository, the EKS control plane, two m6i.large nodes, MSK Serverless, and a network load balancer for Grafana |
| GitHub `deploy`           | after you approve `demo` again for the release. With no plan changes, that is the only gate. With plan changes, it also waits until Apply has succeeded | No new hourly resources. Builds the producer image, pushes it to the repository, applies the producer, Prometheus, and Grafana, and installs the Flink Kubernetes Operator |
| Your laptop               | `make tf-bootstrap` once, then `make kubeconfig`, `make flink-operator`, and `make open-grafana` after the deploy, and `make tf-network-down` when you are finished | Bootstrap once. Destroy removes the VPC, the registry, the image repository, MSK, the Grafana load balancer, and the cluster |

The NAT gateway is about $0.045 per hour. The EKS control plane is about $0.10 per hour, and the two nodes are about $0.192 per hour together. MSK Serverless is about $0.10 per hour plus a small charge for its one partition and the data written. The Grafana load balancer is about $0.023 per hour plus a small charge for the traffic. Approving a plan that adds them starts those charges. `make tf-network-down` stops them. The shape is in [docs/architecture/02-cloud-service-map.md](docs/architecture/02-cloud-service-map.md).

Do this once, before the pull request. Use an IAM user that can create S3, IAM, and VPC resources in `us-east-1`. Root is only for opening the account.

```sh
aws sts get-caller-identity
make tf-bootstrap
```

`make tf-bootstrap` creates the state bucket `rag-demo-tfstate-<account id>` and the role `rag-demo-github-deploy`. Its state file stays on the laptop at `infra/terraform/bootstrap/terraform.tfstate` (gitignored). Keep that file. Run it again when the role policy changes. The plan calls Glue, ECR, EKS, MSK, and Elastic Load Balancing, and those calls fail until this apply adds those permissions to the role. Run it again before approving a plan that creates the Grafana load balancer, so the role can create that load balancer and attach it to the nodes.

Then in the GitHub repository settings:

1. Variables: add `AWS_ACCOUNT_ID` with the 12-digit account id.
2. Environments: create `demo` and add yourself as a required reviewer. Apply and the deploy release both wait on this environment. Approving one does not approve the other.

Open the pull request after those exist. The plan is published on the Actions run. When the plan has changes, approve the `demo` deployment to apply it. A plan with no changes skips that approval. Until you approve a plan that has changes, nothing new is created in the VPC. Unblock deploy is the release, and it waits on `demo` too. With an empty plan that approval is the only gate. With a plan that has changes, it waits until Apply has succeeded and then for a second approval of `demo`. Leave it unapproved when you want the producer to stay undeployed.

After Apply and the deploy release, point kubectl at the cluster. The GitHub role that created it is already an admin. `make kubeconfig` grants the IAM user in your current AWS session and writes `~/.kube/config`.

```sh
make kubeconfig
kubectl get nodes
kubectl -n rag get pods
make open-grafana
```

`make open-grafana` prints the dashboard URL. `kubectl -n rag port-forward svc/grafana 3000:3000` opens the same dashboard at `http://localhost:3000`. `kubectl delete job producer -n rag` stops the producer.

Bring the network down from the laptop when you are finished. This uses your IAM user, not GitHub:

```sh
make tf-network-down
```

The state bucket and the GitHub role stay. They have no hourly charge, and the next apply needs the bucket. To remove them as well, run `make tf-network-down` first, then `make tf-bootstrap-down`.

`make tf-plan-network` and `make tf-network-up` do the same plan and apply from the laptop if you are not using the GitHub deployment.
