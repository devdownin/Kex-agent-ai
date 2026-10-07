#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Kex Agent AI Contributors
set -euo pipefail
exec python3 "$(dirname "$0")/../scripts/forecast-stack.py" diagnose "$@"
