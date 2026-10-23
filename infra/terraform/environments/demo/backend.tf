terraform {
  backend "s3" {
    key          = "environments/demo/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
