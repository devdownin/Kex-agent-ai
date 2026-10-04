#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Kex Agent AI Contributors
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo 'Usage: scripts/evals-run.sh MODEL [REPORT_PATH [BASELINE_REPORT_PATH]]' >&2
  exit 2
fi
provider="${KEX_AGENT_LLM_PROVIDER:-anthropic}"
case "$provider" in
  anthropic) : "${ANTHROPIC_API_KEY:?Set ANTHROPIC_API_KEY for a real provider evaluation}" ;;
  openai) : "${OPENROUTER_API_KEY:?Set OPENROUTER_API_KEY for a real provider evaluation}" ;;
  *) echo 'Provider must be anthropic or openai' >&2; exit 2 ;;
esac
export KEX_RUN_AGENT_EVALS=true
export KEX_EVAL_MODEL="$1"
export KEX_EVAL_REPORT="${2:-target/agent-evals/candidate.json}"
export KEX_EVAL_BASELINE="${3:-}"
export KEX_EVAL_REPETITIONS="${KEX_EVAL_REPETITIONS:-3}"
if ! [[ "$KEX_EVAL_REPETITIONS" =~ ^([2-9]|10)$ ]]; then
  echo 'KEX_EVAL_REPETITIONS must be 2 through 10' >&2
  exit 2
fi
export KEX_EVAL_REVISION="${KEX_EVAL_REVISION:-$(git rev-parse HEAD)}"
evaluation_marker="$(mktemp)"
trap 'rm -f -- "$evaluation_marker"' EXIT
./mvnw -B --no-transfer-progress test -Dtest=AgentReliabilityEvalTest -DexcludedGroups=
[[ -s "$KEX_EVAL_REPORT" && "$KEX_EVAL_REPORT" -nt "$evaluation_marker" ]] \
  || { echo 'Evaluation produced no fresh report (check eval tag selection)' >&2; exit 1; }
