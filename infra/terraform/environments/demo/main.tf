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

module "msk" {
  source = "../../modules/msk"

  name                     = var.name
  vpc_id                   = module.networking.vpc_id
  subnet_ids               = module.networking.private_subnet_ids
  client_security_group_id = module.compute_eks.cluster_security_group_id
  oidc_provider_arn        = module.compute_eks.oidc_provider_arn
  oidc_issuer              = module.compute_eks.oidc_issuer
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

module "grafana_access" {
  source = "../../modules/grafana_access"

  name                   = var.name
  vpc_id                 = module.networking.vpc_id
  public_subnet_ids      = module.networking.public_subnet_ids
  node_security_group_id = module.compute_eks.cluster_security_group_id
  autoscaling_group_name = module.compute_eks.node_group_autoscaling_group_name
}
