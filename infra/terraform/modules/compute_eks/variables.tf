variable "name" {
  type        = string
  description = "Cluster name. Nodes, roles, and the EBS CSI role use this prefix."
}

variable "kubernetes_version" {
  type        = string
  description = "EKS version in standard support. Extended support is a higher hourly rate."
  default     = "1.37"
}

variable "cluster_subnet_ids" {
  type        = list(string)
  description = "Public and private subnets for the control plane network interfaces. Two AZs."
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "Private subnets for the node group. One node per subnet."
}

variable "instance_type" {
  type        = string
  description = "On-demand instance type for the node group. linux/amd64."
  default     = "m6i.large"
}

variable "node_count" {
  type        = number
  description = "Fixed node count. Minimum, desired, and maximum are the same so the group cannot scale itself."
  default     = 2
}

variable "cluster_admin_principal_arns" {
  type        = list(string)
  description = "Extra IAM principals granted cluster admin. The principal that applies the cluster is already an admin."
  default     = []
}
