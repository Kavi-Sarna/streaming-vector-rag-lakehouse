variable "name" {
  type        = string
  description = "MSK Serverless cluster name. The producer IAM role is named <name>-producer."
}

variable "vpc_id" {
  type        = string
  description = "VPC for the broker security group."
}

variable "subnet_ids" {
  type        = list(string)
  description = "Private subnets in two Availability Zones. MSK Serverless requires at least two."
}

variable "client_security_group_id" {
  type        = string
  description = "Security group on the EKS nodes. Broker port 9098 accepts traffic from this group."
}

variable "oidc_provider_arn" {
  type        = string
  description = "EKS OIDC provider ARN. The producer service account assumes its role through this provider."
}

variable "oidc_issuer" {
  type        = string
  description = "EKS OIDC issuer host, without https://."
}

variable "topic" {
  type        = string
  description = "Topic the producer creates and writes. One partition keeps the demo on the smallest MSK partition charge."
  default     = "document.events.v1"
}

variable "producer_namespace" {
  type        = string
  description = "Namespace of the producer service account. Matches infra/k8s."
  default     = "rag"
}

variable "producer_service_account" {
  type        = string
  description = "Producer service account name. Matches infra/k8s."
  default     = "producer"
}
