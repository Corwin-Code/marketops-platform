#!/usr/bin/env bash
# Fresh local/CI PostgreSQL namespace. Never imports repository .env.local.
set -euo pipefail
repository_root=$(cd "$(dirname "$0")/../.." && pwd)
fixture_dir=$(mktemp -d /tmp/marketops-ad-browser.XXXXXX)
fixture_name="marketops-ad-browser-$(basename "$fixture_dir" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9-')"
fixture_network="${fixture_name}-net"
cleanup() {
  docker rm -f "$fixture_name" >/dev/null 2>&1 || true
  docker network rm "$fixture_network" >/dev/null 2>&1 || true
  rm -rf "$fixture_dir"
}
trap cleanup EXIT INT TERM
umask 077
migration_password=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
application_password=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
postgres_password=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
issuer_password=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
# Only this fresh PostgreSQL container joins the namespace. Its sole published
# endpoint binds loopback; internal-network publishing is unavailable on some hosts.
docker network create --driver bridge --opt com.docker.network.bridge.enable_icc=false "$fixture_network" >/dev/null
docker run --detach --name "$fixture_name" --network "$fixture_network" --publish 127.0.0.1::5432 \
  --env POSTGRES_DB=marketops --env "POSTGRES_PASSWORD=$postgres_password" \
  --env "MARKETOPS_DB_MIGRATION_PASSWORD=$migration_password" --env "MARKETOPS_DB_APP_PASSWORD=$application_password" \
  --mount "type=bind,src=$repository_root/infra/compose/postgres-init/01-init-roles.sh,dst=/docker-entrypoint-initdb.d/01-init-roles.sh,readonly" \
  --mount "type=bind,src=$repository_root/infra/compose/postgres-init/sql,dst=/docker-entrypoint-initdb.d/sql,readonly" \
  postgres:17.6-bookworm@sha256:f3bd19c606e442c3d7bdfa8002e03fe260a1023351e0ea4598032022b68dd6e3 >/dev/null
for attempt in $(seq 1 60); do
  if docker exec "$fixture_name" pg_isready -h 127.0.0.1 -U postgres -d marketops >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$fixture_name" pg_isready -h 127.0.0.1 -U postgres -d marketops >/dev/null
fixture_port=$(docker port "$fixture_name" 5432/tcp | awk -F: 'NR==1 {print $NF}')
if [[ ! "$fixture_port" =~ ^[0-9]+$ || "$fixture_port" == 5432 ]]; then
  printf '%s\n' 'Dedicated browser database port was not established' >&2; exit 1
fi
# This login exists only in this new synthetic database.
docker exec -i --env "AD_BROWSER_ISSUER_PASSWORD=$issuer_password" "$fixture_name" \
  psql -X -v ON_ERROR_STOP=1 -U postgres -d marketops >/dev/null <<'SQL'
\getenv ip AD_BROWSER_ISSUER_PASSWORD
ALTER ROLE marketops_identity_issuer LOGIN PASSWORD :'ip';
SQL
export MARKETOPS_AD_BROWSER_CONFIG="$fixture_dir/application.properties"
cat > "$MARKETOPS_AD_BROWSER_CONFIG" <<PROPERTIES
MARKETOPS_DB_PORT=$fixture_port
MARKETOPS_DB_APP_PASSWORD=$application_password
MARKETOPS_DB_MIGRATION_PASSWORD=$migration_password
marketops.identity.invocation.jdbc-url=jdbc:postgresql://127.0.0.1:$fixture_port/marketops
marketops.identity.invocation.username=marketops_identity_issuer
marketops.identity.invocation.password=$issuer_password
PROPERTIES
cd "$repository_root/frontend/marketops-console"
npx playwright test --config=playwright.advertising.config.ts "$@"
