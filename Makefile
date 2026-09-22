# MarketOps Russia — local development entry points.
#
# Commands use repository-relative paths, so the shell never has to reinterpret
# an absolute clone path that contains whitespace or punctuation.

ENV_LOCAL := .env.local
FRONTEND_ENV_LOCAL := frontend/marketops-console/.env.local
COMPOSE_FILE := infra/compose/docker-compose.yml
BACKEND_DIR := backend/marketops-server
FRONTEND_DIR := frontend/marketops-console
COMPOSE_PROJECT_NAME ?= marketops-local

COMPOSE := docker compose --project-name "$(COMPOSE_PROJECT_NAME)" --env-file "$(ENV_LOCAL)" -f "$(COMPOSE_FILE)"
MVNW := ./mvnw -B -ntp
API ?= http://127.0.0.1:8080
AI_KEY_FILE ?= $(HOME)/.marketops-platform/dashscope_api_key.txt

.DEFAULT_GOAL := help

.PHONY: help require-repo-root require-env-local env-init bootstrap \
        up down reset backend-run backend-build frontend-install frontend-dev frontend-build \
        ai-provider

help: ## Show the available targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' Makefile \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-18s %s\n", $$1, $$2}'

require-repo-root:
	@test -f "$(BACKEND_DIR)/pom.xml" \
	  || { echo 'FATAL: run make from the repository root.' >&2; exit 1; }

require-env-local: require-repo-root
	@test -f "$(ENV_LOCAL)" \
	  || { echo 'FATAL: missing .env.local — run `make env-init` first.' >&2; exit 1; }

env-init: require-repo-root ## Generate the ignored local environment files
	@python3 scripts/init_local_env.py --target all

bootstrap: require-repo-root ## Prepare the local configuration files
	@if test -f "$(ENV_LOCAL)" && test -f "$(FRONTEND_ENV_LOCAL)"; then \
	  echo 'bootstrap: preserving the complete existing ignored configuration'; \
	elif test ! -e "$(ENV_LOCAL)" && test ! -e "$(FRONTEND_ENV_LOCAL)"; then \
	  python3 scripts/init_local_env.py --target all; \
	else \
	  echo 'FATAL: local configuration is incomplete; restore or remove both ignored files before bootstrap.' >&2; \
	  exit 1; \
	fi

up: require-env-local ## Start the local database
	@$(COMPOSE) up -d --wait

down: require-env-local ## Stop the local database and keep its data
	@$(COMPOSE) down

reset: require-env-local ## DESTRUCTIVE: stop the database and delete its volume
	@echo 'This deletes the $(COMPOSE_PROJECT_NAME) PostgreSQL volume and all local data.'
	@printf 'Type yes to continue: ' && read answer && test "$$answer" = yes
	@$(COMPOSE) down -v

backend-run: require-env-local ## Run the backend against the local database
	@cd "$(BACKEND_DIR)" && \
	  SPRING_CONFIG_IMPORT='file:../../.env.local[.properties]' \
	  $(MVNW) spring-boot:run -Dspring-boot.run.profiles=local

backend-build: require-repo-root ## Compile and package the backend
	@cd "$(BACKEND_DIR)" && $(MVNW) package

frontend-install: require-repo-root ## Install frontend dependencies from the lockfile
	@cd "$(FRONTEND_DIR)" && npm ci

frontend-dev: require-repo-root ## Start the frontend development server
	@cd "$(FRONTEND_DIR)" && npm run dev

frontend-build: require-repo-root ## Type-check and build the frontend
	@cd "$(FRONTEND_DIR)" && npm run build

ai-provider: require-repo-root ## Install the model key and register the Qwen provider (backend running)
	@python3 scripts/register_ai_provider.py --api "$(API)" \
	  $(if $(wildcard $(AI_KEY_FILE)),--install-key "$(AI_KEY_FILE)") $(if $(REACTIVATE),--reactivate)
