#!/usr/bin/env bash
# Runs the fresh-clone full entry at the current commit with the pinned toolchain,
# and records Docker resources before and after, so other projects are shown untouched.
set -uo pipefail
OUT=/private/tmp/claude-501/-Users-chzhengx-Code-personal-marketops-platform/5229e63c-0445-4510-8e6c-ea1bf84d1bc8/scratchpad/evidence-s4/b2
mkdir -p "$OUT"
unset JAVA_TOOL_OPTIONS
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.10-zulu/zulu-21.jdk/Contents/Home"
export PATH="$HOME/Library/Application Support/fnm/node-versions/v24.19.0/installation/bin:$JAVA_HOME/bin:$PATH"
export TZ=UTC LANG=C.UTF-8
cd /Users/chzhengx/Code/personal/marketops-platform

snapshot() {
  {
    echo "# $1 $(date -u +%FT%TZ)"
    echo "## containers (name, compose project)"
    docker ps -a --format '{{.Names}}	{{.Label "com.docker.compose.project"}}' | sort
    echo "## volumes"
    docker volume ls -q | sort
    echo "## networks"
    docker network ls --format '{{.Name}}' | sort
  } > "$OUT/docker-$1.txt" 2>&1
}

{
  echo "commit $(git rev-parse HEAD) tree $(git rev-parse 'HEAD^{tree}')"
  echo "status-lines $(git status --porcelain | wc -l)"
  java -version 2>&1 | head -1
  echo "node $(node --version) npm $(npm --version)"
  docker version --format 'docker server {{.Server.Version}} client {{.Client.Version}}'
  /bin/bash --version | head -1
  bash --version | head -1
} > "$OUT/context.txt" 2>&1

snapshot before
start=$(date -u +%FT%TZ)
bash scripts/fresh_clone_check.sh > "$OUT/fresh-clone-full.log" 2>&1
status=$?
end=$(date -u +%FT%TZ)
snapshot after
printf 'start %s\nend %s\nexit %s\n' "$start" "$end" "$status" > "$OUT/result.txt"
cat "$OUT/result.txt"
exit "$status"
