SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f infra/compose/docker-compose.yml
BOOTSTRAP := infra/terraform/bootstrap
DEMO := infra/terraform/environments/demo

.PHONY: help lint test ci smoke docker-up docker-down terraform backend-hcl tf-bootstrap tf-network-up tf-network-down tf-bootstrap-down tf-plan-network

help: ## Show this help
	@grep -hE '^[a-z0-9-]+:.*?## ' $(MAKEFILE_LIST) \
	  | awk -F':.*?## ' '{printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

lint: ## Lint Go and validate the Buf workspace
	./ci/lint.sh

test: ## Run the unit tests
	./ci/test.sh

ci: ## Run everything CI runs
	./ci/run.sh

smoke: ## Start the Compose project and tear it down
	./ci/smoke.sh

terraform: ## Format-check and validate Terraform. Does not call AWS
	./ci/terraform.sh

backend-hcl: ## Write demo/backend.hcl from the bootstrap bucket name
	@if bucket="$$(terraform -chdir=$(BOOTSTRAP) output -raw state_bucket_name 2>/dev/null)"; then \
	  printf 'bucket = "%s"\n' "$$bucket" > $(DEMO)/backend.hcl; \
	  echo "wrote $(DEMO)/backend.hcl (bucket=$$bucket)"; \
	elif [ -f $(DEMO)/backend.hcl ]; then \
	  echo "using existing $(DEMO)/backend.hcl"; \
	else \
	  echo "Run make tf-bootstrap first." >&2; \
	  exit 1; \
	fi

tf-bootstrap: ## Create the state bucket and GitHub deploy role
	terraform -chdir=$(BOOTSTRAP) init -input=false
	terraform -chdir=$(BOOTSTRAP) apply

tf-network-up: backend-hcl ## Create the VPC and NAT gateway
	terraform -chdir=$(DEMO) init -backend-config=backend.hcl -input=false
	terraform -chdir=$(DEMO) apply

tf-network-down: backend-hcl ## Destroy the VPC and stop the NAT gateway charge
	terraform -chdir=$(DEMO) init -backend-config=backend.hcl -input=false
	terraform -chdir=$(DEMO) destroy

tf-bootstrap-down: ## Destroy the state bucket and GitHub role. Run tf-network-down first
	terraform -chdir=$(BOOTSTRAP) init -input=false
	terraform -chdir=$(BOOTSTRAP) destroy

tf-plan-network: backend-hcl ## Plan the VPC. Needs AWS credentials and an applied bootstrap
	terraform -chdir=$(DEMO) init -backend-config=backend.hcl -input=false
	terraform -chdir=$(DEMO) plan -target=module.networking

docker-up: ## Start the Compose project
	$(COMPOSE) up -d --wait

docker-down: ## Stop the Compose project
	$(COMPOSE) down --remove-orphans
