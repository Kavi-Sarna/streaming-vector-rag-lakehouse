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
