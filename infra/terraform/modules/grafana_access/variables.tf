variable "name" {
  type        = string
  description = "Stack name. The load balancer and its security group are named <name>-grafana."
}

variable "vpc_id" {
  type        = string
  description = "VPC for the load balancer security group."
}

variable "public_subnet_ids" {
  type        = list(string)
  description = "Public subnets, one per AZ. The load balancer listens here."
}

variable "node_security_group_id" {
  type        = string
  description = "Security group on the EKS nodes. The load balancer forwards Grafana to this group."
}

variable "autoscaling_group_name" {
  type        = string
  description = "Managed node group Auto Scaling group. Its instances are the load balancer targets."
}

variable "node_port" {
  type        = number
  description = "Node port for Grafana. Must match the Service nodePort in infra/k8s/producer-dashboard.yaml."
  default     = 30080
}

variable "listener_port" {
  type        = number
  description = "Public port. make open-grafana allows this port from one address."
  default     = 80
}
