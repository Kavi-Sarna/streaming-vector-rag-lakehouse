# System overview

This repository is an event-driven streaming RAG platform with a transactional lakehouse catalog. Technical markdown arrives as CDC events. A streaming job turns each event into versioned chunks and embeddings. A gRPC gateway streams an answer for one tenant at a time.

The same Protobuf contracts run locally in Docker and, for a gated demo, on an ephemeral AWS account. CI prints a Terraform plan and waits for a manual approval before anything is applied. After the demo, a separate workflow drains workloads, purges S3 object versions, and destroys the stack.

## Ingest path

A Go producer publishes `DocumentEvent` Protobuf messages for insert, update, and delete. The query API is `v1.RAGService/StreamQuery`. On MSK, each record is a Glue Schema Registry frame: an 18-byte header and then those Protobuf bytes. Local Redpanda records stay raw Protobuf. Locally the bus is Redpanda on port 9092. In AWS it is MSK Serverless on port 9098, authenticated with IAM.

A Flink job keys the stream by document id and drops a version it has already processed. It chunks markdown while keeping headers, code blocks, and lists intact. An async embedding step calls a local 384-dimension mock or Amazon Bedrock Titan Text Embeddings V2 at 1024 dimensions. An embedding response older than the latest processed version for that document is discarded before it reaches either sink.

Accepted chunks are written twice:

- Apache Iceberg Parquet, on MinIO locally and on S3 with the Glue Data Catalog in AWS.
- Redis Stack, under a key of tenant, document, and chunk index. A Lua script writes the vector only when the incoming version is at least the version already stored.

Deletes become Iceberg equality tombstones and matching removals in Redis.

## Query path

The Go gateway serves `RAGService/StreamQuery` as plaintext gRPC on port 9090. Locally that is `localhost`. In AWS a Network Load Balancer forwards TCP 9090.

A query is embedded, then checked against a Redis semantic cache filtered by tenant. Identical queries already in flight share one upstream model call. A cache miss runs a tenant-isolated vector search, reranks the hits, and streams tokens back. Reranking is a local lexical scorer or Cohere Rerank on Bedrock. Token streaming is a local mock or Claude on Bedrock.

## Local and cloud runtimes

| Concern           | Local                                 | AWS                                                   |
| ----------------- | ------------------------------------- | ----------------------------------------------------- |
| Network           | Docker network                        | VPC in two AZs, one NAT gateway, us-east-1            |
| Orchestration     | Docker Compose                        | EKS, two m6i.large nodes, Flink Kubernetes Operator   |
| Bus               | Redpanda :9092                        | MSK Serverless :9098, IAM                             |
| Contracts         | Raw Protobuf                          | Glue Schema Registry                                  |
| Lakehouse         | Silo (MinIO S3 API), Hadoop catalog   | S3, Glue Data Catalog                                 |
| Vectors           | Redis Stack                           | Redis Stack on EKS                                    |
| Embeddings        | Mock, 384 dimensions                  | Titan Text Embeddings V2, 1024 dimensions             |
| Rerank            | Lexical overlap                       | Cohere Rerank 3.5                                     |
| LLM               | Mock stream                           | Claude 3.5 Sonnet                                     |
| Entry             | localhost:9090                        | Network Load Balancer, TCP 9090                       |
| Telemetry         | Jaeger, Prometheus, Grafana           | ADOT to X-Ray and CloudWatch                          |
| Identity          | None                                  | IRSA and GitHub OIDC                                  |

## Repository layout

```
api/          Protobuf contracts
go/           Go module: commands and internal packages
flink/        Flink source tree. Generated Java stubs live here. The job comes with the chunking change
infra/        Compose, Dockerfiles, Terraform, and Kubernetes manifests
ci/           Check scripts. Workflows and the Makefile both call these.
scripts/      Provisioning, seed, and verification scripts
testdata/     Technical markdown corpus
docs/         Architecture and walkthroughs
```

GitHub Actions and Buildkite files stay at the repository root, because those products only read workflows from there. The workflow files install tools and call `ci/run.sh`. The checks themselves live in that script.

## Local topology

`make docker-up` starts Redpanda, Silo, Redis Stack, a Flink JobManager and TaskManager, the mock embedder, Prometheus, and Grafana. Silo is the local object store: the official MinIO image is no longer anonymously pullable, and Silo serves the same S3 API. The bucket is `lakehouse`.

The producer, the Flink job, the sinks, and the query gateway are not part of this stack. The producer runs on the host and exposes publish counts on port 9095. Prometheus scrapes that port. Grafana at `http://localhost:3000` shows those counts. The same binary is packaged as an image that downloads the seed corpus during the build. `infra/k8s/` runs it as a Job, with the same event counts and an in-cluster Grafana dashboard. `.github/workflows/deploy.yml` pushes that image to ECR and applies the manifests after the cluster exists. Flink is running and idle. Redpanda has the topic `document.events.v1`. Host clients use `localhost:9092`. Containers on the Compose network use `redpanda:9093`.
