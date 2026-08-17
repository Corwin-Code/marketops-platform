#!/bin/sh
# Create the two database roles the platform runs as.
#
# The official image executes every file in /docker-entrypoint-initdb.d exactly
# once, when the data directory is empty. Re-running against an initialised
# volume is not attempted, so the SQL below is written for a first run and fails
# loudly rather than tolerating an existing role.
#
# Passwords are handed to psql through the container environment, never through
# command arguments or logs. A sufficiently privileged host process can inspect
# container environments, so this prevents incidental disclosure rather than
# claiming that the host is a security boundary.

set -eu

if [ -z "${MARKETOPS_DB_MIGRATION_PASSWORD:-}" ]; then
  echo "init-roles: MARKETOPS_DB_MIGRATION_PASSWORD is not set; refusing to create a role without a password" >&2
  exit 1
fi

if [ -z "${MARKETOPS_DB_APP_PASSWORD:-}" ]; then
  echo "init-roles: MARKETOPS_DB_APP_PASSWORD is not set; refusing to create a role without a password" >&2
  exit 1
fi

script_dir=$(dirname "$0")

psql --username "${POSTGRES_USER}" \
     --dbname "${POSTGRES_DB}" \
     --set ON_ERROR_STOP=1 \
     --no-psqlrc \
     --quiet \
     --file "${script_dir}/sql/01-roles.sql"

echo "init-roles: created marketops_migration and marketops_app"
