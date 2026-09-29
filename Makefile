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
PILOT ?= pilot
CAPABILITY ?= connectivity
# Recorded as the operator in the audit of every maintenance call the pilot makes.
OPERATOR ?= owner-local

.DEFAULT_GOAL := help

.PHONY: help require-repo-root require-env-local env-init bootstrap \
        up down reset backend-run backend-build frontend-install frontend-dev frontend-build \
        ai-provider ozon-probe ozon-setup ozon-reviewer ozon-verify ozon-run ozon-resolve ozon-normalize

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

ozon-probe: require-repo-root ## Ozon pilot: check the key, keep CAPABILITY's evidence; OFFICIAL_SOURCE=<swagger.json>
	@python3 scripts/ozon_pilot.py probe --pilot "$(PILOT)" --capability "$(CAPABILITY)" \
	  $(if $(OFFICIAL_SOURCE),--official-source-file "$(OFFICIAL_SOURCE)") $(if $(DATE),--date "$(DATE)")

ozon-setup: require-repo-root ## Ozon pilot: register account, credential and CAPABILITY's rows (backend running)
	@python3 scripts/ozon_pilot.py setup --api "$(API)" --operator "$(OPERATOR)" --pilot "$(PILOT)" \
	  --capability "$(CAPABILITY)" $(if $(SUPERSEDE),--supersede-mapping)

ozon-reviewer: require-repo-root ## Ozon pilot: make Keycloak user SUBJECT a reviewing Owner (local)
	@test -n "$(SUBJECT)" || { echo 'usage: make ozon-reviewer SUBJECT=<keycloak-user-id>' >&2; exit 2; }
	@python3 scripts/ozon_pilot.py reviewer --api "$(API)" --operator "$(OPERATOR)" --subject "$(SUBJECT)"

ozon-verify: require-repo-root ## Ozon pilot: submit and approve CAPABILITY's probe evidence as two Owners
	@python3 scripts/ozon_pilot.py verify --api "$(API)" --operator "$(OPERATOR)" --pilot "$(PILOT)" \
	  --capability "$(CAPABILITY)" $(if $(AGAIN),--again)

ozon-run: require-repo-root ## Ozon pilot: run CAPABILITY's job (windowed: DAYS=<n> DATE=<last UTC day>)
	@python3 scripts/ozon_pilot.py run --api "$(API)" --operator "$(OPERATOR)" --pilot "$(PILOT)" \
	  --capability "$(CAPABILITY)" $(if $(DATE),--date "$(DATE)") $(if $(DAYS),--days "$(DAYS)")

ozon-resolve: require-repo-root ## Ozon pilot: retry or close CAPABILITY's BLOCKED run (RESOLUTION=retry|close REASON=...)
	@test -n "$(RESOLUTION)" -a -n "$(REASON)" || { echo "usage: make ozon-resolve CAPABILITY=<c> RESOLUTION=retry|close REASON='<what was found>'" >&2; exit 2; }
	@python3 scripts/ozon_pilot.py resolve --api "$(API)" --operator "$(OPERATOR)" --pilot "$(PILOT)" \
	  --capability "$(CAPABILITY)" --resolution "$(RESOLUTION)" --reason "$(REASON)"

ozon-normalize: require-repo-root ## Ozon pilot: normalize what CAPABILITY's job stored (backend running)
	@python3 scripts/ozon_pilot.py normalize --api "$(API)" --operator "$(OPERATOR)" --pilot "$(PILOT)" \
	  --capability "$(CAPABILITY)"
