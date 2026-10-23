output "vpc_id" {
  description = "VPC that later modules place EKS, MSK, and the gateway into."
  value       = aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "Subnets for the NAT gateway and the public Network Load Balancer."
  value       = [for az in local.azs : aws_subnet.public[az].id]
}

output "private_subnet_ids" {
  description = "Subnets for EKS nodes and MSK Serverless."
  value       = [for az in local.azs : aws_subnet.private[az].id]
}

output "availability_zones" {
  description = "The two AZs the subnets occupy, in subnet order."
  value       = local.azs
}

output "nat_gateway_id" {
  description = "The single NAT gateway shared by both private subnets."
  value       = aws_nat_gateway.this.id
}
