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

## Layout

```
api/     Protobuf contracts.
go/      Go module. Generated Go stubs live in go/gen/v1.
flink/   Flink source tree. Generated Java stubs live in flink/src/main/java. The job comes later.
infra/   Compose for the local stack, and Terraform for the state bucket and VPC. Kubernetes later.
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
- `make test` runs the Go unit tests.
- `make terraform` checks formatting and runs `terraform validate`. It does not call AWS.
- `make smoke` starts the local stack, checks it, and removes it.

A failure reproduces locally with `make ci`. In Actions the Compose step fails if Docker is missing. On a laptop without Docker it is skipped with a warning.

## Contracts

`api/proto/v1/document_event.proto` is one CDC change for a markdown document: tenant, document id, version, and insert, update, or delete. `api/proto/v1/rag_service.proto` is `v1.RAGService/StreamQuery`.

```sh
make proto-gen
make test
```

`make proto-gen` formats the contracts and writes Go stubs to `go/gen/v1` and Java stubs to `flink/src/main/java`. `make test` compiles the Go stubs and checks the Glue wire frame. `make lint` checks Buf lint and format.

Local records are the raw Protobuf bytes. A cloud record puts an 18-byte Glue header in front of those bytes: version `0x03`, a compression byte, and the schema version id. The demo stack registers `document_event` in the Glue registry `rag-demo`.

## Local stack

`make docker-up` starts the backing services and leaves them running. `make docker-health` checks them from the host. `make docker-down` stops them. No Flink job or query gateway is submitted.

## CDC producer

`make seed-data` downloads three corpora into `testdata/`: 40 Kubernetes enhancement proposals in `keps/`, 40 arXiv abstracts in `arxiv/`, and 40 HuggingFace `ag_news` articles in `hf/`. The HuggingFace export needs `pip install datasets`. `make seed-data-keps`, `make seed-data-arxiv`, and `make seed-data-hf` refresh one source.

`make producer` publishes 300 `DocumentEvent` records to `document.events.v1` on `localhost:9092`. Redpanda must already be running. It reads every markdown file under `testdata/`. Each record is raw Protobuf, keyed by the path of the file, such as `keps/sig-node_2000-graceful-node-shutdown`. The pause between records is random, from 200ms to 1.5s, so the run lasts about three to seven minutes. Each file is inserted, then updated, and then deleted about one time in five. Which files are deleted changes every run. The command prints the seed so the same choices can be repeated with `--seed`. Pass `ARGS='--sleep-min=100ms --sleep-max=400ms --burst=30 --delete-chance=0.3'` to change the pace and the delete share. `--sleep-max=0 --rate=50` publishes at a fixed 50 per second. `--security=aws-msk-iam` and `--glue-schema-version` are the MSK path. The steps are in [docs/walkthroughs/02-cdc-event-producer.md](docs/walkthroughs/02-cdc-event-producer.md).

| Service        | Address                              | What it is                                                                        |
| -------------- | ------------------------------------ | --------------------------------------------------------------------------------- |
| Redpanda       | `localhost:9092`                     | Kafka for the host. Flink uses `redpanda:9093` on the Compose network. Topic `document.events.v1`. |
| Silo           | `localhost:9000`, console `:9001`    | MinIO S3 API. User `minio`, password `minio12345`. Bucket `lakehouse`.            |
| Redis Stack    | `localhost:6379`                     | Vector index and cache. No password.                                              |
| Flink          | `http://localhost:8081`              | JobManager and one TaskManager. No job is running.                                |
| Mock embedder  | `http://localhost:8091/embed`        | `POST {"text":"..."}` returns a deterministic 384-dimension vector.               |

The object store image is `pgsty/silo`. It speaks the MinIO S3 API. The official MinIO image is no longer anonymously pullable.

## AWS network

Two workflows, two different jobs. `ci` never calls AWS. `network` does, and only after the one-time bootstrap below exists.

|                           | What runs                                                                        | Creates resources                         |
| ------------------------- | -------------------------------------------------------------------------------- | ----------------------------------------- |
| GitHub `ci`               | lint, unit tests, `terraform validate`, Compose smoke                            | No                                        |
| GitHub `network`          | on a pull request: plan the VPC, Glue registry, and EKS cluster, then print the plan  | Not yet                              |
| GitHub `network` apply    | only when that plan has changes, after you approve the `demo` environment        | Yes. VPC, one NAT gateway, the Glue registry, the EKS control plane, and two m6i.large nodes |
| Your laptop               | `make tf-bootstrap` once, and `make tf-network-down` when you are finished       | Bootstrap once. Destroy removes the VPC, the registry, and the cluster |

The NAT gateway is about $0.045 per hour. The EKS control plane is about $0.10 per hour, and the two nodes are about $0.192 per hour together. Approving a plan that adds them starts those charges. `make tf-network-down` stops them. The shape is in [docs/architecture/02-cloud-service-map.md](docs/architecture/02-cloud-service-map.md).

Do this once, before the pull request. Use an IAM user that can create S3, IAM, and VPC resources in `us-east-1`. Root is only for opening the account.

```sh
aws sts get-caller-identity
make tf-bootstrap
```

`make tf-bootstrap` creates the state bucket `rag-demo-tfstate-<account id>` and the role `rag-demo-github-deploy`. Its state file stays on the laptop at `infra/terraform/bootstrap/terraform.tfstate` (gitignored). Keep that file. Run it again when the role policy changes. The plan calls Glue and EKS, and those calls fail until this apply adds those permissions to the role.

Then in the GitHub repository settings:

1. Variables: add `AWS_ACCOUNT_ID` with the 12-digit account id.
2. Environments: create `demo` and add yourself as a required reviewer.

Open the pull request after those exist. The plan is published on the Actions run. When the plan has changes, approve the `demo` deployment to apply it. A plan with no changes skips that approval. Until you approve a plan that has changes, nothing new is created in the VPC.

Bring the network down from the laptop when you are finished. This uses your IAM user, not GitHub:

```sh
make tf-network-down
```

The state bucket and the GitHub role stay. They have no hourly charge, and the next apply needs the bucket. To remove them as well, run `make tf-network-down` first, then `make tf-bootstrap-down`.

`make tf-plan-network` and `make tf-network-up` do the same plan and apply from the laptop if you are not using the GitHub deployment.
