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

module "ecr" {
  source = "../../modules/ecr"

  name = "${var.name}-producer"
}

module "compute_eks" {
  source = "../../modules/compute_eks"

  name               = var.name
  private_subnet_ids = module.networking.private_subnet_ids
  cluster_subnet_ids = concat(
    module.networking.public_subnet_ids,
    module.networking.private_subnet_ids,
  )
  cluster_admin_principal_arns = var.cluster_admin_principal_arns
}
