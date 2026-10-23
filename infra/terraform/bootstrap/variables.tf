variable "region" {
  type        = string
  description = "Region for the state bucket and the GitHub OIDC provider."
  default     = "us-east-1"
}

variable "state_bucket_name" {
  type        = string
  description = "Remote state bucket. Empty selects rag-demo-tfstate-<account id>."
  default     = ""
}

variable "github_repository" {
  type        = string
  description = "GitHub repository allowed to assume the deploy role, as owner/name."
  default     = "Kavi-Sarna/streaming-vector-rag-lakehouse"
}

variable "github_owner_id" {
  type        = string
  description = "Numeric GitHub owner id. Included in the OIDC subject for repositories created after 2026-07-15."
  default     = "135118806"
}

variable "github_repository_id" {
  type        = string
  description = "Numeric GitHub repository id. Included in the OIDC subject for repositories created after 2026-07-15."
  default     = "1429327369"
}
