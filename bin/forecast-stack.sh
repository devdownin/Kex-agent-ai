#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Kafka Explorer Contributors
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_dir"
if [[ $# -gt 1 || ($# -eq 1 && $1 != --prepare-only && $1 != --demo) ]]; then
  echo 'Usage: bin/forecast-stack.sh [--prepare-only | --demo]' >&2
  exit 2
fi
umask 077
state_dir=.forecast-stack
mkdir -p "$state_dir/config"
# Atomic no-clobber writes: subsequent runs retain credentials and reviewed configuration.
if [[ ! -e "$state_dir/.env" ]]; then
  command -v openssl >/dev/null || { echo 'openssl is required to generate credentials.' >&2; exit 1; }
  env_tmp=$(mktemp "$state_dir/.env.XXXXXX")
  trap 'rm -f "$env_tmp"' EXIT
  {
    echo "FORECAST_POSTGRES_PASSWORD=$(openssl rand -hex 32)"
    echo "TIMESFM_TOKEN=$(openssl rand -hex 32)"
    echo "EXPLORER_MCP_AUTH_TOKEN=$(openssl rand -hex 32)"
    echo "KEX_AGENT_API_KEY=$(openssl rand -hex 32)"
    echo 'FORECAST_ENVIRONMENTS=local'
  } > "$env_tmp"
  if ln "$env_tmp" "$state_dir/.env" 2>/dev/null; then :; fi
  rm -f "$env_tmp"
  trap - EXIT
fi
if [[ ! -e "$state_dir/config/forecasts.yml" ]]; then
  (set -o noclobber; printf '# Save the reviewed configuration assistant export here.\n{}\n' > "$state_dir/config/forecasts.yml") || true
fi
# The container runs as UID 10001; its mounted metadata must be readable. Secrets stay 0600
# under the private state directory and are never mounted as configuration.
chmod 755 "$state_dir/config"
chmod 644 "$state_dir/config/forecasts.yml"
if [[ ${1:-} == --prepare-only ]]; then
  echo 'Prepared .forecast-stack/config/forecasts.yml and private credentials; no containers started.'
  exit 0
fi
command -v docker >/dev/null || { echo 'Docker with Compose v2 is required. Files are prepared; install Docker and rerun.' >&2; exit 1; }
echo 'Starting Kex-anHarness, KafkaExplorer, Kafka, PostgreSQL and local forecasts: first run downloads the pinned model; TimesFM defaults to 4 CPUs / 8 GiB.'
echo 'Open http://localhost:8080, Metrics Forecast, and export the reviewed configuration to .forecast-stack/config/forecasts.yml.'
echo 'MCP URL: http://localhost:8080/mcp. The bearer credential is in .forecast-stack/.env.'
compose_args=()
if [[ -f .env ]]; then compose_args+=(--env-file .env); fi
compose_args+=(--env-file "$state_dir/.env" -f docker-compose.yml -f compose/forecasts.yml)
command -v python3 >/dev/null || { echo "Python 3 is required for startup diagnostics." >&2; exit 1; }
echo "[1/4] Validation de la configuration"
docker compose "${compose_args[@]}" config --quiet
echo "[2/4] Construction des images"
docker compose "${compose_args[@]}" build
echo "[3/4] Démarrage : préchargement du modèle, PostgreSQL, TimesFM et applications"
docker compose "${compose_args[@]}" up -d
# Bind-file contents do not change Compose service hashes; Spring reads approvals at startup.
docker compose "${compose_args[@]}" restart explorer
echo "[4/4] Vérification de santé et de la connexion MCP"
python3 scripts/forecast-stack.py wait --timeout 300
if [[ ${1:-} == --demo ]]; then python3 scripts/forecast-stack.py demo; fi
echo "Stack prête. Agent : http://localhost:8081 · KafkaExplorer : http://localhost:8080"
