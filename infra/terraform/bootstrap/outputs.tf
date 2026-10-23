output "state_bucket_name" {
  description = "Pass this as bucket in infra/terraform/environments/demo/backend.hcl."
  value       = aws_s3_bucket.state.bucket
}

output "github_deploy_role_arn" {
  description = "Role GitHub Actions assumes. No long-lived access keys."
  value       = aws_iam_role.github_deploy.arn
}

output "region" {
  description = "Region the state bucket lives in."
  value       = var.region
}
