#!/usr/bin/env bash
# Runs `bash scripts/fresh_clone_check.sh full` once at the current commit with the pinned
# toolchain, keeps its real exit code, and records Docker, port and host state before,
# during (watch.py) and after the run. Everything is written to $1, outside the worktree.
set -uo pipefail
OUT="$1"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO=/Users/chzhengx/Code/personal/marketops-platform
mkdir -p "$OUT"
unset JAVA_TOOL_OPTIONS
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.10-zulu/zulu-21.jdk/Contents/Home"
export PATH="$HOME/Library/Application Support/fnm/node-versions/v24.19.0/installation/bin:$JAVA_HOME/bin:$PATH"
export TZ=UTC LANG=C.UTF-8
cd "$REPO"

snapshot() {
  {
    echo "# $1 $(date -u +%FT%TZ)"
    echo "## containers (name, compose project, status)"
    docker ps -a --format '{{.Names}}	{{.Label "com.docker.compose.project"}}	{{.Status}}' | sort
    echo "## volumes"
    docker volume ls -q | sort
    echo "## networks"
    docker network ls --format '{{.Name}}' | sort
  } > "$OUT/docker-$1.txt" 2>&1
}

probe() {
  python3 -c 'import errno, socket, sys; s = socket.socket(); s.settimeout(0.4); sys.exit(0 if s.connect_ex(("127.0.0.1", int(sys.argv[1]))) == errno.ECONNREFUSED else 1)' "$1"
}

ports() {
  echo "# $1 $(date -u +%FT%TZ)"
  for port in 8080 9999 8082 4173; do
    if probe "$port"; then state="refused (free)"; else state="not refused (in use)"; fi
    owners=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -Fc 2>/dev/null | sed -n 's/^c//p' | sort -u | paste -sd+ -)
    echo "127.0.0.1:$port $state listeners=${owners:--}"
  done
}

{
  echo "commit $(git rev-parse HEAD) tree $(git rev-parse 'HEAD^{tree}') branch $(git rev-parse --abbrev-ref HEAD)"
  echo "status-lines $(git status --porcelain | wc -l | tr -d ' ')"
  echo "os $(sw_vers -productName) $(sw_vers -productVersion) $(sw_vers -buildVersion) $(uname -m)"
  echo "host cpus $(sysctl -n hw.ncpu) memory-bytes $(sysctl -n hw.memsize)"
  java -version 2>&1 | head -1
  echo "node $(node --version) npm $(npm --version)"
  docker version --format 'docker server {{.Server.Version}} client {{.Client.Version}}'
  docker info --format 'docker vm cpus {{.NCPU}} memory-bytes {{.MemTotal}} os {{.OperatingSystem}}'
  echo "bash on PATH: $(command -v bash) $(bash --version | head -1)"
  echo "/bin/bash: $(/bin/bash --version | head -1)"
  echo "python3 $(python3 --version 2>&1 | cut -d' ' -f2) git $(git --version | cut -d' ' -f3) make $(make --version | head -1)"
  echo "TMPDIR $TMPDIR"
  echo "JAVA_TOOL_OPTIONS unset by this runner (the tool sandbox sets it; this run is outside the sandbox)"
  echo "proxy variables present: $(env | cut -d= -f1 | grep -E '^(HTTP_PROXY|HTTPS_PROXY|NO_PROXY)$' | sort | paste -sd' ' -) (values not recorded; the host's local proxy on 127.0.0.1:15732)"
} > "$OUT/context.txt" 2>&1

{
  ports before
  echo "## refused inherited variable names present (the entry refuses these)"
  env | cut -d= -f1 | grep -E '^(MARKETOPS_DB_|MARKETOPS_POSTGRES_|SPRING_DATASOURCE_|SPRING_FLYWAY_|SPRING_CONFIG_|SPRING_APPLICATION_JSON|SERVER_PORT)' || echo "none"
  echo "## existing fresh-clone workspaces in TMPDIR"
  ls -d "${TMPDIR%/}"/marketops-fresh.* 2>/dev/null || echo "none"
  echo "## /tmp/marketops-verify-local-config.log"
  ls -l /tmp/marketops-verify-local-config.log 2>/dev/null || echo "absent"
} > "$OUT/preflight.txt" 2>&1

snapshot before
STOP="$OUT/.watch-stop"
rm -f "$STOP"
start_epoch=$(date +%s)
python3 "$HERE/watch.py" --out "$OUT" --log "$OUT/fresh-clone-full.log" --tmpdir "${TMPDIR%/}" \
  --started-after "$start_epoch" --stop-file "$STOP" > "$OUT/watch.out" 2>&1 &
watch_pid=$!

start=$(date -u +%FT%TZ)
bash scripts/fresh_clone_check.sh full > "$OUT/fresh-clone-full.log" 2>&1
status=$?
end=$(date -u +%FT%TZ)

cp -p /tmp/marketops-verify-local-config.log "$OUT/verify-local-config.backend.log" 2>/dev/null || true
snapshot after
{
  ports after
  echo "## fresh-clone workspaces in TMPDIR after the run"
  ls -d "${TMPDIR%/}"/marketops-fresh.* 2>/dev/null || echo "none"
} > "$OUT/postflight.txt" 2>&1
sleep 150
snapshot later
printf 'start %s\nend %s\nexit %s\n' "$start" "$end" "$status" > "$OUT/result.txt"
touch "$STOP"
wait "$watch_pid"
echo "watcher exit $?" >> "$OUT/watch.out"
cat "$OUT/result.txt"
exit "$status"
