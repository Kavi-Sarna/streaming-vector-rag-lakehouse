# Cloud network and state

The demo AWS account has two Terraform roots. Bootstrap is applied once from a laptop and kept. The demo composition is what a session plans, applies, and destroys.

## Bootstrap

`infra/terraform/bootstrap` uses local state. It creates:

- An S3 bucket, `rag-demo-tfstate-<account id>`, with versioning, AES-256, and public access blocked. The demo composition stores its state here and locks with an S3 lock file, so there is no DynamoDB table.
- A GitHub OIDC provider and the role `rag-demo-github-deploy`. GitHub Actions for this repository can assume it from `main` or from the `demo` environment. The role can read and write that state bucket and manage EC2 networking. Later modules add their own permissions. There are no long-lived access keys.

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

Public subnets are tagged for external load balancers. Private subnets are tagged for internal load balancers and for an EKS cluster named `rag-demo`. EKS, MSK, and the lakehouse bucket are not created here.

## Apply and destroy

The commands are in the README. `make terraform` checks formatting and runs `terraform validate` with no AWS account. `make tf-plan-network` plans this module after `backend.hcl` exists.
