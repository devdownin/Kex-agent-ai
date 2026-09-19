// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operator-owned routes: a prompt can select a task, never supply an endpoint or credential. */
@ConfigurationProperties("kex.models")
public record LlmRoutingProperties(boolean enabled, boolean localOnly,
                                   Map<String, Endpoint> endpoints, Map<Task, List<String>> routes) {

    public enum Task { CHAT, TRIAGE, DIAGNOSTIC }

    public enum Provider { OPENAI, ANTHROPIC, OLLAMA, VLLM }

    public LlmRoutingProperties {
        endpoints = endpoints == null ? Map.of() : Map.copyOf(endpoints);
        Map<String, Endpoint> configuredEndpoints = endpoints;
        routes = routes == null ? Map.of() : routes.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
        if (enabled) {
            if (routes.getOrDefault(Task.CHAT, List.of()).isEmpty()) {
                throw new IllegalArgumentException("kex.models.routes.chat must contain an endpoint");
            }
            routes.forEach((task, names) -> {
                if (names.isEmpty() || names.stream().distinct().count() != names.size()) {
                    throw new IllegalArgumentException("Model routes must be non-empty and contain no duplicates");
                }
                names.forEach(name -> {
                    Endpoint endpoint = configuredEndpoints.get(name);
                    if (endpoint == null) throw new IllegalArgumentException("Unknown model endpoint: " + name);
                    if (localOnly && endpoint.provider() != Provider.OLLAMA && endpoint.provider() != Provider.VLLM) {
                        throw new IllegalArgumentException("local-only forbids hosted model routes");
                    }
                    String host = URI.create(endpoint.baseUrl()).getHost();
                    if (localOnly && !List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host)) {
                        throw new IllegalArgumentException("local-only requires a loopback model endpoint");
                    }
                });
            });
        }
    }

    public List<String> route(Task task) {
        return routes.getOrDefault(task, routes.get(Task.CHAT));
    }

    public record Endpoint(Provider provider, String baseUrl, String apiKey, String model,
                           Duration timeout, Integer maxTokens, Double temperature) {
        public Endpoint {
            if (provider == null || model == null || model.isBlank()) {
                throw new IllegalArgumentException("Each model endpoint requires provider and model");
            }
            boolean local = provider == Provider.OLLAMA || provider == Provider.VLLM;
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = switch (provider) {
                    case OLLAMA -> "http://localhost:11434/v1";
                    case VLLM -> "http://localhost:8000/v1";
                    case OPENAI -> "https://api.openai.com/v1";
                    case ANTHROPIC -> "https://api.anthropic.com";
                };
            }
            URI uri = URI.create(baseUrl);
            if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Model endpoint must be an HTTP(S) URL without credentials or query");
            }
            if (apiKey == null || apiKey.isBlank()) {
                if (!local) throw new IllegalArgumentException("Hosted model endpoints require an API key");
                apiKey = "kex-local";
            }
            timeout = timeout == null ? Duration.ofSeconds(45) : timeout;
            maxTokens = maxTokens == null ? 4096 : maxTokens;
            temperature = temperature == null ? 0.2 : temperature;
            if (timeout.isNegative() || timeout.isZero() || maxTokens < 1
                    || !Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
                throw new IllegalArgumentException("Invalid model timeout, max-tokens or temperature");
            }
        }

        @Override
        public String toString() {
            return "Endpoint[provider=" + provider + ", model=" + model + ", apiKey=REDACTED]";
        }
    }
}
