SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f infra/compose/docker-compose.yml
BOOTSTRAP := infra/terraform/bootstrap
DEMO := infra/terraform/environments/demo

.PHONY: help lint test ci smoke docker-up docker-down docker-health docker-build-producer terraform proto-gen backend-hcl tf-bootstrap tf-network-up tf-network-down tf-bootstrap-down tf-plan-network open-grafana close-grafana seed-data seed-data-keps seed-data-arxiv seed-data-hf producer

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

proto-gen: ## Generate Go and Java stubs from the Protobuf contracts
	buf format -w
	buf generate
	buf lint
	( cd go && go mod tidy )

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

tf-network-up: backend-hcl ## Create the VPC, NAT gateway, Glue registry, producer ECR repository, EKS cluster, MSK Serverless, and the Grafana load balancer
	terraform -chdir=$(DEMO) init -backend-config=backend.hcl -input=false
	terraform -chdir=$(DEMO) apply

tf-network-down: backend-hcl ## Destroy the demo stack and stop NAT, EKS, MSK, load balancer, and node charges
	terraform -chdir=$(DEMO) init -backend-config=backend.hcl -input=false
	terraform -chdir=$(DEMO) destroy

tf-bootstrap-down: ## Destroy the state bucket and GitHub role. Run tf-network-down first
	terraform -chdir=$(BOOTSTRAP) init -input=false
	terraform -chdir=$(BOOTSTRAP) destroy

tf-plan-network: backend-hcl ## Plan the VPC, Glue registry, producer ECR repository, EKS cluster, MSK Serverless, and the Grafana load balancer. Needs AWS credentials and an applied bootstrap
	terraform -chdir=$(DEMO) init -backend-config=backend.hcl -input=false
	terraform -chdir=$(DEMO) plan -target=module.networking -target=module.schema_registry -target=module.ecr -target=module.compute_eks -target=module.msk -target=module.grafana_access

open-grafana: ## Allow this machine's public IP to open the cloud Grafana dashboard
	./scripts/open-grafana.sh

close-grafana: ## Remove that address from the Grafana load balancer
	./scripts/open-grafana.sh --close

docker-up: ## Start Redpanda, Silo, Redis, Flink, the mock embedder, Prometheus, and Grafana
	$(COMPOSE) up -d --wait --build

docker-down: ## Stop the Compose project
	$(COMPOSE) down --remove-orphans

docker-health: ## Check the local stack from the host
	./scripts/verify-infra-health.sh

docker-build-producer: ## Build the producer image. The image downloads the corpus itself
	docker build -f infra/docker/producer.Dockerfile -t rag-lakehouse-producer:local .

seed-data: ## Download KEPs, arXiv abstracts, and HuggingFace articles
	./scripts/seed-corpus.sh

seed-data-keps: ## Download 40 Kubernetes KEPs into testdata/keps
	./scripts/download-seed-docs.sh

seed-data-arxiv: ## Fetch arXiv abstracts into testdata/arxiv
	@mkdir -p testdata/arxiv
	curl -fsSL "https://export.arxiv.org/api/query?search_query=cat:cs.CL+OR+cat:cs.DC&start=0&max_results=40" -o testdata/arxiv/feed.xml
	python3 scripts/fetch-arxiv-seed.py --input testdata/arxiv/feed.xml --output-dir testdata/arxiv

seed-data-hf: ## Export a HuggingFace sample into testdata/hf
	python3 -W ignore::UserWarning scripts/export-hf-seed.py --dataset ag_news --output-dir testdata/hf

producer: ## Publish CDC events to local Redpanda. Override with ARGS='--sleep-max=400ms --burst=30'
	cd go && go run ./cmd/producer --input=../testdata $(ARGS)
