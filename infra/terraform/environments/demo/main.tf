provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = var.name
      ManagedBy = "terraform"
    }
  }
}

module "networking" {
  source = "../../modules/networking"

  name     = var.name
  vpc_cidr = var.vpc_cidr
}

module "schema_registry" {
  source = "../../modules/schema_registry"

  name = var.name
  schemas = {
    document_event = file("${path.module}/../../../../api/proto/v1/document_event.proto")
  }
}
