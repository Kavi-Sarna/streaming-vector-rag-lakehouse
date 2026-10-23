variable "region" {
  type        = string
  description = "Region for the demo session. MSK Serverless and the Bedrock models in this design are used from us-east-1."
  default     = "us-east-1"
}

variable "name" {
  type        = string
  description = "Name of the VPC and the future EKS cluster."
  default     = "rag-demo"
}

variable "vpc_cidr" {
  type        = string
  description = "CIDR for the demo VPC."
  default     = "10.0.0.0/16"
}
