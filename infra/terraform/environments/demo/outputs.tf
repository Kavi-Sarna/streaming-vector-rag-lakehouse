output "vpc_id" {
  description = "Demo VPC."
  value       = module.networking.vpc_id
}

output "public_subnet_ids" {
  description = "Public subnets, one per AZ."
  value       = module.networking.public_subnet_ids
}

output "private_subnet_ids" {
  description = "Private subnets, one per AZ, sharing one NAT gateway."
  value       = module.networking.private_subnet_ids
}

output "availability_zones" {
  description = "AZs occupied by the subnets."
  value       = module.networking.availability_zones
}

output "schema_registry_name" {
  description = "Glue Schema Registry that holds the Protobuf contracts."
  value       = module.schema_registry.registry_name
}

output "msk_bootstrap_brokers" {
  description = "MSK Serverless IAM bootstrap brokers."
  value       = module.msk.bootstrap_brokers_sasl_iam
}

output "producer_role_arn" {
  description = "IAM role for the producer service account."
  value       = module.msk.producer_role_arn
}

output "producer_repository_url" {
  description = "ECR repository the deploy job pushes the producer image to."
  value       = module.ecr.repository_url
}

output "cluster_name" {
  description = "EKS cluster name."
  value       = module.compute_eks.cluster_name
}

output "cluster_endpoint" {
  description = "Public Kubernetes API endpoint."
  value       = module.compute_eks.cluster_endpoint
}
