SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f infra/compose/docker-compose.yml

.PHONY: help lint test ci smoke up down

help: ## Show this help
	@grep -hE '^[a-z0-9-]+:.*?## ' $(MAKEFILE_LIST) \
	  | awk -F':.*?## ' '{printf "  \033[36m%-8s\033[0m %s\n", $$1, $$2}'

lint: ## Lint Go and validate the Buf workspace
	./ci/lint.sh

test: ## Run the unit tests
	./ci/test.sh

ci: ## Run everything CI runs
	./ci/run.sh

smoke: ## Start the Compose project and tear it down
	./ci/smoke.sh

up: ## Start the Compose project
	$(COMPOSE) up -d --wait

down: ## Stop the Compose project
	$(COMPOSE) down --remove-orphans
