// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Serveur MCP streamable-HTTP minimal, protégé par bearer. Écrit à la main plutôt que simulé :
 * ce qu'on veut verrouiller ici est le transport réel (en-têtes, JSON-RPC, notifications), pas
 * le comportement d'un mock.
 *
 * <p>Le catalogue d'outils et les réponses de {@code tools/call} sont scriptables
 * ({@link #withToolsList} / {@link #withToolCallResult}) : les évals de jugement du modèle
 * (voir {@code ModelJudgmentEvalTest}) ont besoin d'un outil crédible rendant un résultat précis,
 * là où les tests de transport se contentent du couple {@code echo}/« pong » par défaut.
 */
public final class FakeMcpServer implements AutoCloseable {

    public static final String TOKEN = "jeton-de-test";

    private static final String DEFAULT_TOOLS_LIST = """
            {"tools":[{"name":"echo","description":"Renvoie son argument",
             "inputSchema":{"type":"object","properties":{"texte":{"type":"string"}}}}]}""";

    private static final String DEFAULT_CALL_RESULT = """
            {"content":[{"type":"text","text":"pong"}],"isError":false}""";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<Integer> unauthorized = new CopyOnWriteArrayList<>();
    private volatile String toolsList = DEFAULT_TOOLS_LIST;
    private final Map<String, String> toolCallResults = new ConcurrentHashMap<>();

    public FakeMcpServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/mcp", this::handle);
        this.server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public List<String> methods() {
        return List.copyOf(methods);
    }

    public int unauthorizedCount() {
        return unauthorized.size();
    }

    /** Remplace le catalogue {@code tools/list} par défaut (un seul outil, {@code echo}). */
    public FakeMcpServer withToolsList(String toolsListJson) {
        this.toolsList = toolsListJson;
        return this;
    }

    /** Réponse de {@code tools/call} pour un outil nommé ; « pong » pour les autres. */
    public FakeMcpServer withToolCallResult(String toolName, String resultJson) {
        toolCallResults.put(toolName, resultJson);
        return this;
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
                    .formatted(JSON.writeValueAsString(id), result(method, request)));
        }
    }

    private String result(String method, JsonNode request) {
        return switch (method) {
            case "initialize" -> """
                    {"protocolVersion":"2025-06-18","capabilities":{"tools":{},"resources":{}},
                     "serverInfo":{"name":"faux-serveur","version":"1.0.0"}}""";
            case "tools/list" -> toolsList;
            case "tools/call" -> toolCallResults.getOrDefault(toolName(request), DEFAULT_CALL_RESULT);
            case "resources/list" -> """
                    {"resources":[{"uri":"test://ressource","name":"ressource",
                     "description":"Une ressource","mimeType":"text/plain"}]}""";
            case "resources/read" -> """
                    {"contents":[{"uri":"test://ressource","mimeType":"text/plain","text":"contenu"}]}""";
            default -> "{}";
        };
    }

    private static String toolName(JsonNode request) {
        return request.path("params").path("name").asText("");
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
