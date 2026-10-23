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
api/     Protobuf workspace. Sources arrive with the contracts change.
go/      Go module.
infra/   Compose, and Terraform for the state bucket and VPC. Kubernetes and Dockerfiles later.
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
- `make smoke` starts the placeholder Compose container and removes it.

A failure reproduces locally with `make ci`. In Actions the Compose step fails if Docker is missing. On a laptop without Docker it is skipped with a warning.

`make docker-up` starts that placeholder and leaves it running. `make docker-down` stops it. The local Redpanda, MinIO, Redis Stack, and Flink stack replaces it in a later change.

## AWS network

Two workflows, two different jobs. `ci` never calls AWS. `network` does, and only after the one-time bootstrap below exists.

|                           | What runs                                                                     | Creates resources                         |
| ------------------------- | ----------------------------------------------------------------------------- | ----------------------------------------- |
| GitHub `ci`               | lint, unit tests, `terraform validate`, Compose smoke                         | No                                        |
| GitHub `network`          | on a pull request: plan the VPC, print it on the job summary, then wait       | Not yet                                   |
| GitHub `network` apply    | after you approve the `demo` environment deployment                           | Yes. VPC and one NAT gateway              |
| Your laptop               | `make tf-bootstrap` once, and `make tf-network-down` when you are finished    | Bootstrap once. Destroy removes the VPC   |

The NAT gateway is about $0.045 per hour. Approving the deployment starts that charge. `make tf-network-down` stops it. No instances are launched. The shape is in [docs/architecture/02-cloud-service-map.md](docs/architecture/02-cloud-service-map.md).

Do this once, before the pull request. Use an IAM user that can create S3, IAM, and VPC resources in `us-east-1`. Root is only for opening the account.

```sh
aws sts get-caller-identity
make tf-bootstrap
```

`make tf-bootstrap` creates the state bucket `rag-demo-tfstate-<account id>` and the role `rag-demo-github-deploy`. Its state file stays on the laptop at `infra/terraform/bootstrap/terraform.tfstate` (gitignored). Keep that file.

Then in the GitHub repository settings:

1. Variables: add `AWS_ACCOUNT_ID` with the 12-digit account id.
2. Environments: create `demo` and add yourself as a required reviewer.

Open the pull request after those exist. The plan is published on the Actions run. Approve the `demo` deployment to apply that plan. Until you approve, nothing in the VPC is created.

Bring the network down from the laptop when you are finished. This uses your IAM user, not GitHub:

```sh
make tf-network-down
```

The state bucket and the GitHub role stay. They have no hourly charge, and the next apply needs the bucket. To remove them as well, run `make tf-network-down` first, then `make tf-bootstrap-down`.

`make tf-plan-network` and `make tf-network-up` do the same plan and apply from the laptop if you are not using the GitHub deployment.
