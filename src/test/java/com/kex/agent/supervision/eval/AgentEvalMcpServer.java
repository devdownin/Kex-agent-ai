// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision.eval;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** MCP fake at the network boundary; the real SDK, tool policy and model run unchanged. */
public final class AgentEvalMcpServer implements AutoCloseable {
    public static final String TOKEN = "agent-evaluation-only";
    private final HttpServer server;
    private final List<AgentEvalScoring.Call> calls = new CopyOnWriteArrayList<>();
    private volatile AgentEvalCorpus.Scenario scenario;

    public AgentEvalMcpServer() throws IOException {
        scenario = AgentEvalCorpus.load().getFirst();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void select(AgentEvalCorpus.Scenario selected) {
        scenario = selected;
        calls.clear();
    }

    public List<AgentEvalScoring.Call> calls() {
        return List.copyOf(calls);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            JsonNode request = AgentEvalCorpus.JSON.readTree(exchange.getRequestBody());
            JsonNode id = request.get("id");
            if (id == null || id.isNull()) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            Object result = switch (request.path("method").asText()) {
                case "initialize" -> Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "agent-eval-fixture", "version", "1.0"));
                case "tools/list" -> Map.of("tools", scenario.tools());
                case "tools/call" -> call(request);
                case "resources/list" -> Map.of("resources", List.of());
                case "prompts/list" -> Map.of("prompts", List.of());
                default -> Map.of();
            };
            byte[] response = AgentEvalCorpus.JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                    "result", result)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Mcp-Session-Id", "eval-session");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    private Object call(JsonNode request) throws IOException {
        String name = request.path("params").path("name").asText();
        Map<String, Object> arguments = AgentEvalCorpus.JSON.convertValue(request.path("params").path("arguments"),
                new TypeReference<>() { });
        calls.add(new AgentEvalScoring.Call(name, arguments));
        Map<String, Object> payload = scenario.responses().get(name);
        if (payload == null) {
            return Map.of("content", List.of(Map.of("type", "text", "text", "Unknown fixture tool")), "isError", true);
        }
        return Map.of("content", List.of(Map.of("type", "text", "text", AgentEvalCorpus.JSON.writeValueAsString(payload))),
                "isError", Boolean.TRUE.equals(payload.get("_isError")));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
