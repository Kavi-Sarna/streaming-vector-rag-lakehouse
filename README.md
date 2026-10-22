# streaming-vector-rag-lakehouse

Event-driven streaming RAG platform and transactional lakehouse catalog. CDC events for technical markdown are chunked and embedded in a streaming job, stored in Apache Iceberg and a Redis vector index, and served through a streaming gRPC query API.

Local development uses Go, Apache Flink, Redpanda, Apache Iceberg, Redis Stack, MinIO, and Protobuf. An ephemeral AWS deployment uses EKS, MSK Serverless, S3, Glue, Bedrock, and a Network Load Balancer. The runtime map is in [docs/architecture/01-system-overview.md](docs/architecture/01-system-overview.md).

## Prerequisites

- Go 1.27.3
- golangci-lint 2.15.0
- Buf CLI 1.75.0
- Docker Compose v2
- GNU make

## Layout

```
api/     Protobuf workspace. Sources arrive with the contracts change.
go/      Go module.
infra/   Compose now. Terraform, Kubernetes, and Dockerfiles later.
ci/      The checks GitHub Actions runs. `make ci` calls the same scripts.
docs/    Architecture and walkthroughs.
```

## Checks

```sh
make ci
```

`ci/run.sh` is the contract: Go lint, Buf config, unit tests, and a Compose smoke. GitHub Actions calls that script and nothing else, so a CI failure reproduces locally with one command.

The Compose smoke is the conditional step. CI sets `REQUIRE_DOCKER=1`, so a missing daemon fails the job. Locally it is skipped with a warning when Docker is not running.

Individually: `make lint`, `make test`, `make smoke`. `make up` starts the placeholder container and leaves it running; `make down` stops it. The local Redpanda, MinIO, Redis Stack, and Flink stack replaces that container in a later change.
