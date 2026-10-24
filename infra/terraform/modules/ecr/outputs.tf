output "repository_url" {
  description = "ECR repository URL, without a tag."
  value       = aws_ecr_repository.producer.repository_url
}
