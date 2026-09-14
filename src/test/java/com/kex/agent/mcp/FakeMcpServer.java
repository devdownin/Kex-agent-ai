// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Serveur MCP streamable-HTTP minimal, protégé par bearer. Écrit à la main plutôt que simulé :
 * ce qu'on veut verrouiller ici est le transport réel (en-têtes, JSON-RPC, notifications), pas
 * le comportement d'un mock.
 */
final class FakeMcpServer implements AutoCloseable {

    static final String TOKEN = "jeton-de-test";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<Integer> unauthorized = new CopyOnWriteArrayList<>();

    FakeMcpServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/mcp", this::handle);
        this.server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    List<String> methods() {
        return List.copyOf(methods);
    }

    int unauthorizedCount() {
        return unauthorized.size();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                unauthorized.add(401);
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String method = request.path("method").asText("");
            methods.add(method);

            JsonNode id = request.get("id");
            if (id == null || id.isNull()) { // notification : pas de réponse, juste un accusé
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            // L'identifiant est réémis tel quel : le SDK peut l'envoyer en nombre comme en chaîne.
            respond(exchange, "{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":%s}"
                    .formatted(JSON.writeValueAsString(id), result(method)));
        }
    }

    private static String result(String method) {
        return switch (method) {
            case "initialize" -> """
                    {"protocolVersion":"2025-06-18","capabilities":{"tools":{},"resources":{}},
                     "serverInfo":{"name":"faux-serveur","version":"1.0.0"}}""";
            case "tools/list" -> """
                    {"tools":[{"name":"echo","description":"Renvoie son argument",
                     "inputSchema":{"type":"object","properties":{"texte":{"type":"string"}}}}]}""";
            case "tools/call" -> """
                    {"content":[{"type":"text","text":"pong"}],"isError":false}""";
            case "resources/list" -> """
                    {"resources":[{"uri":"test://ressource","name":"ressource",
                     "description":"Une ressource","mimeType":"text/plain"}]}""";
            case "resources/read" -> """
                    {"contents":[{"uri":"test://ressource","mimeType":"text/plain","text":"contenu"}]}""";
            default -> "{}";
        };
    }

    private void respond(HttpExchange exchange, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Mcp-Session-Id", "test-session");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
