"""Serveur MCP streamable-HTTP minimal, protégé par bearer.

Sert à vérifier en CI que la stack compose se monte et se parle : l'agent doit découvrir ce
serveur et son outil, jeton compris. Utiliser Kafka SQL Explorer ici ferait dépendre la CI d'un
pull Docker Hub anonyme et de son quota par IP, pour ne rien tester de plus côté agent.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

TOKEN = os.environ["MCP_STUB_TOKEN"]

RESULTS = {
    "initialize": {
        "protocolVersion": "2025-06-18",
        "capabilities": {"tools": {}, "resources": {}},
        "serverInfo": {"name": "mcp-stub", "version": "1.0.0"},
    },
    "tools/list": {
        "tools": [{
            "name": "echo",
            "description": "Renvoie son argument",
            "inputSchema": {"type": "object", "properties": {"texte": {"type": "string"}}},
        }]
    },
    "tools/call": {"content": [{"type": "text", "text": "pong"}], "isError": False},
    "resources/list": {"resources": []},
    "ping": {},
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.send_response(401)
            self.end_headers()
            return
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])) or b"{}")
        if "id" not in request or request["id"] is None:
            self.send_response(202)
            self.end_headers()
            return
        result = RESULTS.get(request.get("method"))
        payload = ({"jsonrpc": "2.0", "id": request["id"], "result": result} if result is not None
                   else {"jsonrpc": "2.0", "id": request["id"],
                         "error": {"code": -32601, "message": "unknown method"}})
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Mcp-Session-Id", "ci")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        # Sonde de disponibilité pour compose ; le transport MCP lui-même n'utilise que POST.
        self.send_response(200 if self.path == "/health" else 405)
        self.end_headers()


HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
