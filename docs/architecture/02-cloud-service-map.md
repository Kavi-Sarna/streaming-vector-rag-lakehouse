# Cloud network and state

The demo AWS account has two Terraform roots. Bootstrap is applied once from a laptop and kept. The demo composition is what a session plans, applies, and destroys.

## Bootstrap

`infra/terraform/bootstrap` uses local state. It creates:

- An S3 bucket, `rag-demo-tfstate-<account id>`, with versioning, AES-256, and public access blocked. The demo composition stores its state here and locks with an S3 lock file, so there is no DynamoDB table.
- A GitHub OIDC provider and the role `rag-demo-github-deploy`. GitHub Actions for this repository can assume it from a pull request, from `main`, or from the `demo` environment. The role can read and write that state bucket and manage the VPC, the Glue registry, the producer image repository, MSK Serverless, the EKS cluster, and the Grafana load balancer. Later modules add their own permissions. There are no long-lived access keys.

Destroying the demo composition does not delete this bucket or this role. The next session needs both, and neither has an hourly charge.

## Demo network

`infra/terraform/environments/demo` calls `modules/networking`. In `us-east-1` it builds:

| Resource                  | Shape                                                                 |
| ------------------------- | --------------------------------------------------------------------- |
| VPC                       | `10.0.0.0/16`, DNS hostnames on                                       |
| Public subnets            | One `/20` in each of two AZs, default route to an internet gateway    |
| Private subnets           | One `/20` in each of the same AZs                                     |
| NAT gateway               | One, in the first public subnet. Both private route tables use it     |
| Default security group    | No ingress and no egress                                              |

MSK Serverless requires two Availability Zones. A second NAT gateway would add another hourly charge for the whole session, so both private subnets share one.

Public subnets are tagged for external load balancers. Private subnets are tagged for internal load balancers and for an EKS cluster named `rag-demo`. The lakehouse bucket is not created here.

## EKS

`modules/compute_eks` creates cluster `rag-demo` on Kubernetes 1.37 in standard support. The API is public, and IAM decides who can call it. The principal that applies the cluster is a cluster admin. `make kubeconfig` grants the IAM user in the current AWS session and writes `~/.kube/config`. `cluster_admin_principal_arns` adds other principals at apply time.

The node group is two on-demand `m6i.large` instances, Amazon Linux 2023, linux/amd64, one in each private subnet. They use the NAT gateway. Minimum, desired, and maximum are all 2, so the group does not scale itself. The cluster OIDC provider is what later service accounts use. The EBS CSI driver is installed with its own role so a later volume can attach. The node role can pull from ECR in this account. Terraform does not start a workload. After Apply, `.github/workflows/deploy.yml` applies the producer, Prometheus, and Grafana from `infra/k8s`, and installs the Flink Kubernetes Operator.

The control plane is about $0.10 per hour. The two nodes are about $0.192 per hour together. Extended support is not selected. `make tf-network-down` deletes the cluster and the nodes.

## Flink Kubernetes Operator

The deploy job installs the Helm chart `infra/helm/flink-operator` after the cluster exists. That chart bundles Flink Kubernetes Operator `1.16.1`. cert-manager `v1.21.3` is a pre-install hook: the namespace weight is `-20` and the other cert-manager resources are `-10`, so they are ready before the operator creates its webhook certificate. The cert-manager CRDs are in the chart `crds/` directory, which Helm installs before the hooks. The operator Deployment is in namespace `flink-operator`. cert-manager stays in namespace `cert-manager`. `make flink-operator` runs the same install from a laptop that already has `make kubeconfig` and Helm 4.3.1. The dedup job is still submitted only to the local Compose cluster. The operator pods use the existing nodes, so they do not add an hourly AWS charge of their own.

## MSK Serverless

`modules/msk` creates cluster `rag-demo` in the two private subnets. Clients authenticate with IAM on port 9098. The broker security group accepts that port from the EKS node security group. The producer service account `rag/producer` assumes role `rag-demo-producer`, which can create and write `document.events.v1`. The topic is one partition. The producer creates it on the first publish. The deploy job copies the cluster's bootstrap brokers into the Job.

MSK Serverless is about $0.10 per hour, plus a partition-hour and the bytes written. `make tf-network-down` deletes the cluster with the rest of the demo stack.

## Grafana

`modules/grafana_access` creates an internet-facing network load balancer named `rag-demo-grafana` in the public subnets. It listens on port 80 and forwards to node port 30080, which is the Grafana Service in namespace `rag`. Client IP preservation is off, so the node security group accepts that port only from the load balancer. The load balancer security group is created with no client rule. `make open-grafana` reads this machine's public IPv4 address from `checkip.amazonaws.com` and allows that `/32` on port 80. `make close-grafana` removes the rule. The security group does not allow `0.0.0.0/0`.

The load balancer is about $0.023 per hour, plus a small charge for the traffic. `make tf-network-down` deletes it with the rest of the demo stack.

## Producer image repository

`modules/ecr` creates `rag-demo-producer`. Image tags are mutable, the repository is deleted with the demo stack even when it still holds images, and a lifecycle rule keeps the last 10 images. The repository has no hourly charge. Storage is billed for the images that are pushed. The deploy job builds the producer from `infra/docker/producer.Dockerfile`, and that build downloads the seed corpus into the image.

## Apply and destroy

The commands are in the README. `make terraform` checks formatting and runs `terraform validate` with no AWS account. `make tf-plan-network` plans the network, the Glue registry, the producer repository, this cluster, MSK Serverless, and the Grafana load balancer after `backend.hcl` exists. Apply stays in `.github/workflows/network.yml`. Deploys live in `.github/workflows/deploy.yml`. Unblock deploy waits on the same `demo` environment as Apply. An empty plan does not make it wait for Apply. A plan with changes does. Approving Apply does not approve the release. That file then pushes the image, applies `infra/k8s`, and installs the Flink Kubernetes Operator.
