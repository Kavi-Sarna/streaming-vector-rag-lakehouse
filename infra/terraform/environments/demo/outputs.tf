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
