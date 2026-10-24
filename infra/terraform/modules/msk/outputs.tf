output "cluster_arn" {
  description = "MSK Serverless cluster ARN."
  value       = aws_msk_serverless_cluster.this.arn
}

output "bootstrap_brokers_sasl_iam" {
  description = "IAM bootstrap brokers on port 9098."
  value       = aws_msk_serverless_cluster.this.bootstrap_brokers_sasl_iam
}

output "producer_role_arn" {
  description = "Role the producer service account assumes."
  value       = aws_iam_role.producer.arn
}

output "topic" {
  description = "Topic the producer creates on first publish."
  value       = var.topic
}
