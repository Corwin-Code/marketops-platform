#!/bin/sh
# Create the two database roles the platform runs as.
#
# The official image executes every file in /docker-entrypoint-initdb.d exactly
# once, when the data directory is empty. Re-running against an initialised
# volume is not attempted, so the SQL below is written for a first run and fails
# loudly rather than tolerating an existing role.
#
# Passwords are handed to psql through the environment, never through arguments.
# A command line is visible to every process on the host; an environment variable
# of this container is not.

set -eu

for variable in MARKETOPS_DB_MIGRATION_PASSWORD MARKETOPS_DB_APP_PASSWORD; do
  eval "value=\${$variable-}"
  if [ -z "${value}" ]; then
    echo "init-roles: ${variable} is not set; refusing to create a role without a password" >&2
    exit 1
  fi
done

script_dir=$(dirname "$0")

psql --username "${POSTGRES_USER}" \
     --dbname "${POSTGRES_DB}" \
     --set ON_ERROR_STOP=1 \
     --no-psqlrc \
     --quiet \
     --file "${script_dir}/sql/01-roles.sql"

echo "init-roles: created marketops_migration and marketops_app"
