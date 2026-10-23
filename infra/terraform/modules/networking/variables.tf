variable "name" {
  type        = string
  description = "Name prefix for the VPC and the EKS cluster that will use it."
  default     = "rag-demo"
}

variable "vpc_cidr" {
  type        = string
  description = "CIDR for the VPC. Public subnets take the first two /20s, private subnets two /20s in the upper half."
  default     = "10.0.0.0/16"
}
