# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Kex Agent AI Contributors
import contextlib
import importlib.util
import io
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('forecast_stack', Path(__file__).resolve().parents[1] / 'scripts/forecast-stack.py')
stack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stack)


class McpBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.auth_required = True
        self.tools = stack.TOOLS.copy()
        self.sse = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                if owner.auth_required and self.headers.get('Authorization') != 'Bearer private-token':
                    self.send_response(401)
                    self.end_headers()
                    return
                self.send_response(200)
                self.end_headers()
                data = json.dumps({'jsonrpc': '2.0', 'id': 1, 'result': {'tools': [{'name': name} for name in owner.tools]}})
                self.wfile.write((('event: message\ndata: ' + data + '\n\n') if owner.sse else data).encode())

        self.server = HTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.settings = {'services': {'explorer': {'ports': [{'published': self.server.server_port}],
                         'environment': {'EXPLORER_MCP_AUTH_TOKEN': 'private-token'}}}}

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()

    def test_authenticated_catalog_json_and_sse(self):
        stack.check_mcp(self.settings)
        self.sse = True
        stack.check_mcp(self.settings)

    def test_public_mcp_is_a_failure_even_with_all_tools(self):
        self.auth_required = False
        with self.assertRaises(ValueError):
            stack.check_mcp(self.settings)

    def test_missing_forecast_tool_is_a_failure(self):
        self.tools.remove('kex_forecast_metric')
        with self.assertRaises(ValueError):
            stack.check_mcp(self.settings)


class DiagnosticTest(unittest.TestCase):
    def test_empty_catalog_is_configuration_not_infrastructure_failure(self):
        rows = [{'Service': name, 'State': 'running', 'Health': 'healthy'} for name in
                ['kafka', 'forecast-postgres', 'timesfm', 'explorer', 'agent']]
        rows += [{'Service': 'forecast-model-prefetch', 'State': 'exited', 'ExitCode': 0}]
        settings = {'services': {'agent': {'ports': [{'published': 8081}], 'environment': {'KEX_AGENT_API_KEY': 'private-agent-token'}}}}
        output = io.StringIO()
        with patch.object(stack, 'compose', side_effect=lambda *args: json.dumps(rows) if args[0] == 'ps' else '1'), \
             patch.object(stack, 'check_mcp'), \
             patch.object(stack, 'request', return_value={'connected': True, 'catalogComplete': True, 'ready': False}), \
             contextlib.redirect_stdout(output):
            self.assertEqual(stack.diagnose(settings), 0)
        self.assertIn('À CONFIGURER', output.getvalue())
        self.assertNotIn('private-agent-token', output.getvalue())

    def test_unhealthy_service_causes_nonzero_status_without_leaking_secrets(self):
        settings = {'services': {'agent': {'ports': [{'published': 8081}], 'environment': {'KEX_AGENT_API_KEY': 'private-agent-token'}}}}
        output = io.StringIO()
        with patch.object(stack, 'compose', return_value='[]'), patch.object(stack, 'check_mcp', side_effect=ValueError('private-token')), \
             patch.object(stack, 'request', side_effect=ValueError('private-agent-token')), contextlib.redirect_stdout(output):
            self.assertEqual(stack.diagnose(settings), 1)
        self.assertIn('BLOQUÉ', output.getvalue())
        self.assertNotIn('private-token', output.getvalue())
        self.assertNotIn('private-agent-token', output.getvalue())


if __name__ == '__main__':
    unittest.main()
