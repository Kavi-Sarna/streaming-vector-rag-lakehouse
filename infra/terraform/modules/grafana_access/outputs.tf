output "dns_name" {
  description = "Public DNS name of the Grafana network load balancer."
  value       = aws_lb.this.dns_name
}

output "security_group_id" {
  description = "Load balancer security group. make open-grafana adds one address to it."
  value       = aws_security_group.this.id
}
