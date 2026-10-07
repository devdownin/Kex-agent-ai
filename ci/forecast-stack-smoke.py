#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Kex Agent AI Contributors
"""Read-only smoke against a disposable stack with the demo metric installed."""
import importlib.util
import urllib.error
from pathlib import Path

path = Path(__file__).resolve().parents[1] / 'scripts/forecast-stack.py'
spec = importlib.util.spec_from_file_location('forecast_stack', path)
stack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stack)
settings = stack.config()
assert stack.diagnose(settings) == 0, 'Infrastructure diagnosis failed'
base = stack.base_url(settings, 'agent')
try:
    stack.request(base + '/api/agent/forecasts/readiness')
except urllib.error.HTTPError as error:
    assert error.code == 401, 'Agent must reject unauthenticated requests'
else:
    raise AssertionError('Agent accepted an unauthenticated request')
value = stack.request(base + '/api/agent/forecasts/readiness',
                      token=settings['services']['agent']['environment']['KEX_AGENT_API_KEY'])
assert value['connected'] and not value['missingTools'] and value['catalogComplete']
assert value['seriesCount'] == 0 and not value['ready'], 'Empty approvals must remain explicit'
metrics = stack.request(stack.base_url(settings, 'explorer') + '/api/forecasts/candidates')['metrics']
metric = next(row for row in metrics if row['metricId'] == 'forecast-demo-lag')
assert metric['eligible'] and metric['unit'] == 'milliseconds'
print('Real stack: service health, PostgreSQL, TimesFM, MCP auth/tools, agent auth and demo eligibility OK')
print('No inference or model-quality claim: approved series and measured history remain operator decisions.')
