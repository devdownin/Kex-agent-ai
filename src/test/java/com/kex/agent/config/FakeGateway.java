// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/** Une passerelle compatible OpenAI réduite à {@code /models}, pour un contrat testé sur du HTTP réel. */
final class FakeGateway implements AutoCloseable {

    private final HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile String body;
    private volatile int status = 200;

    FakeGateway(String body) throws IOException {
        this.body = body;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/models", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] payload = this.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        this.server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<String> authorizations() {
        return authorizations;
    }

    void respondWith(int status, String body) {
        this.status = status;
        this.body = body;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
