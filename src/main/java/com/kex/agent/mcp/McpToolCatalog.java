// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Introspection et invocation directe des serveurs MCP connectés. Les clients sont initialisés
 * paresseusement ({@code spring.ai.mcp.client.initialized=false}) pour que l'agent démarre même
 * si un serveur distant est indisponible : chaque accès retente l'initialisation des clients
 * encore muets, et {@link McpHealthCheckScheduler} le fait aussi périodiquement, sans attendre
 * qu'une requête entrante s'en charge.
 */
public class McpToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpToolCatalog.class);

    private static final String CLIENT_NAME_SEPARATOR = " - ";
    private static final String CIRCUIT_BREAKER_PREFIX = "mcp-tool-";

    private final List<McpSyncClient> clients;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final Map<String, CircuitBreaker> circuitBreakers;
    private final Retry retry;

    public McpToolCatalog(List<McpSyncClient> clients, ObservationRegistry observationRegistry,
                          CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry,
                          MeterRegistry meterRegistry) {
        this.clients = clients;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.retry = retryRegistry.retry("mcp-tool");

        // Un disjoncteur par connexion, pas un seul partagé : un serveur MCP en panne ne doit pas
        // faire échouer vite les appels vers les autres. Nommé et enregistré dès la construction,
        // pas au premier appel, pour apparaître dans /actuator/prometheus et le statut de l'agent
        // même sans trafic. Le chemin piloté par le modèle (RecordingToolCallbackProvider) reste
        // sur le disjoncteur partagé "mcp-tool" : SyncMcpToolCallback n'expose pas la connexion
        // dont il vient, donc pas moyen de router vers le bon disjoncteur à cet endroit-là.
        this.circuitBreakers = new HashMap<>();
        clients.forEach(client -> {
            String connection = connectionName(client);
            circuitBreakers.put(connection, circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_PREFIX + connection));
            Gauge.builder("kex.mcp.server.up", client, McpToolCatalog::upValue)
                    .description("1 si le client MCP est initialisé, 0 sinon")
                    .tag("connection", connection)
                    .register(meterRegistry);
        });
    }

    // Un appel réseau/stdio par serveur : ne pas exposer sans cache sur un endpoint chaud.
    public List<McpServerInfo> servers() {
        initializePending();
        return clients.stream().map(this::describe).toList();
    }

    /**
     * Invocation directe, sans passage par le modèle : le contrôle d'accès n'est porté que par
     * l'appelant, contrairement au flux ChatClient où le LLM choisit l'outil.
     *
     * <p>Le réessai ne rejoue qu'une indisponibilité explicite du serveur ({@link
     * McpServerUnavailableException}), jamais une erreur de protocole (outil inconnu, argument
     * refusé) qui resterait fausse rejouée. Le disjoncteur, lui, compte tout échec : au-delà du
     * seuil, l'appel échoue tout de suite plutôt que d'attendre le plafond de temps à chaque essai.
     */
    public McpToolResult call(String connection, String tool, Map<String, Object> arguments) {
        CircuitBreaker circuitBreaker = circuitBreakerFor(connection);
        // Ce chemin ne passe pas par Spring AI, donc pas par ses observations : sans ce timer,
        // la latence et les échecs de l'appel direct ne seraient mesurés nulle part. Connexions et
        // noms d'outils sont bornés, la cardinalité le reste aussi.
        Supplier<McpToolResult> invocation = () -> Observation.createNotStarted("kex.mcp.tool.call", observationRegistry)
                .lowCardinalityKeyValue("connection", connection)
                .lowCardinalityKeyValue("tool", tool)
                .observe(() -> {
                    McpSchema.CallToolResult result = client(connection).callTool(
                            new McpSchema.CallToolRequest(tool, arguments == null ? Map.of() : arguments));
                    return new McpToolResult(connection, tool, Boolean.TRUE.equals(result.isError()),
                            textOf(result.content()), result.structuredContent());
                });
        Supplier<McpToolResult> resilient = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry, invocation));
        try {
            return resilient.get();
        }
        catch (CallNotPermittedException ex) {
            throw new McpServerUnavailableException(connection, ex);
        }
    }

    /** Ressources exposées par un serveur : vide si le serveur ne déclare pas la capacité. */
    public List<McpResourceInfo> resources(String connection) {
        McpSyncClient client = client(connection);
        if (!supportsResources(client)) {
            return List.of();
        }
        return client.listResources().resources().stream()
                .map(resource -> new McpResourceInfo(resource.uri(), resource.name(), resource.description(),
                        resource.mimeType(), resource.size()))
                .toList();
    }

    public List<McpResourceContent> readResource(String connection, String uri) {
        McpSyncClient client = client(connection);
        if (!supportsResources(client)) {
            throw new UnsupportedMcpCapabilityException(connection, "resources");
        }
        return client.readResource(new McpSchema.ReadResourceRequest(uri)).contents().stream()
                .map(McpToolCatalog::toContent)
                .toList();
    }

    /**
     * Coût et latence par connexion et par outil, agrégés toutes issues confondues (succès et
     * échec) — le détail par issue n'apporte rien de plus à la console qu'un chiffre à lire vite.
     */
    public List<McpToolMetric> metrics() {
        Map<List<String>, List<Timer>> byConnectionAndTool = meterRegistry.find("kex.mcp.tool.call").timers().stream()
                .collect(Collectors.groupingBy(timer -> List.of(tagOrUnknown(timer, "connection"),
                        tagOrUnknown(timer, "tool"))));
        return byConnectionAndTool.entrySet().stream()
                .map(entry -> {
                    long count = entry.getValue().stream().mapToLong(Timer::count).sum();
                    double totalMs = entry.getValue().stream()
                            .mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS)).sum();
                    return new McpToolMetric(entry.getKey().get(0), entry.getKey().get(1), count,
                            count == 0 ? null : totalMs / count);
                })
                .sorted(Comparator.comparing(McpToolMetric::connection).thenComparing(McpToolMetric::tool))
                .toList();
    }

    private static String tagOrUnknown(Timer timer, String tag) {
        String value = timer.getId().getTag(tag);
        return value == null ? "unknown" : value;
    }

    private static double upValue(McpSyncClient client) {
        return client.isInitialized() ? 1.0 : 0.0;
    }

    private CircuitBreaker circuitBreakerFor(String connection) {
        CircuitBreaker breaker = circuitBreakers.get(connection);
        if (breaker == null) {
            throw new UnknownMcpServerException(connection);
        }
        return breaker;
    }

    private McpSyncClient client(String connection) {
        McpSyncClient client = find(connection).orElseThrow(() -> new UnknownMcpServerException(connection));
        if (!client.isInitialized()) {
            try {
                client.initialize();
            }
            catch (RuntimeException ex) {
                throw new McpServerUnavailableException(connection, ex);
            }
        }
        return client;
    }

    private Optional<McpSyncClient> find(String connection) {
        return clients.stream()
                .filter(candidate -> connection.equals(connectionName(candidate)))
                .findFirst();
    }

    /**
     * Le SDK gère l'initialisation concurrente : pas de verrou côté appelant. Package-private :
     * {@link McpHealthCheckScheduler} la rejoue périodiquement, sans attendre qu'un appel entrant
     * s'en charge à sa place.
     */
    void initializePending() {
        clients.stream().filter(client -> !client.isInitialized()).forEach(client -> {
            try {
                client.initialize();
            }
            catch (RuntimeException ex) {
                log.warn("Serveur MCP '{}' injoignable : {}", connectionName(client), ex.getMessage());
            }
        });
    }

    private McpServerInfo describe(McpSyncClient client) {
        McpSchema.Implementation info = client.getServerInfo();
        McpSchema.InitializeResult initialization = client.getCurrentInitializationResult();
        String connection = connectionName(client);
        CircuitBreaker breaker = circuitBreakers.get(connection);
        return new McpServerInfo(connection,
                info != null ? info.name() : null,
                info != null ? info.version() : null,
                initialization != null ? initialization.protocolVersion() : null,
                client.isInitialized(),
                breaker == null ? null : breaker.getState().name(),
                listTools(client));
    }

    /** {@code spring.ai.mcp.client.name} + " - " + clé de connexion, posé par l'autoconfiguration. */
    private static String connectionName(McpSyncClient client) {
        McpSchema.Implementation clientInfo = client.getClientInfo();
        if (clientInfo == null || clientInfo.name() == null) {
            return "unknown";
        }
        int separator = clientInfo.name().lastIndexOf(CLIENT_NAME_SEPARATOR);
        return separator < 0 ? clientInfo.name() : clientInfo.name().substring(separator + CLIENT_NAME_SEPARATOR.length());
    }

    private static List<McpToolInfo> listTools(McpSyncClient client) {
        if (!client.isInitialized()) {
            return List.of();
        }
        try {
            return client.listTools().tools().stream()
                    .map(tool -> new McpToolInfo(tool.name(), tool.description(), tool.inputSchema()))
                    .toList();
        }
        catch (RuntimeException ex) {
            log.warn("Listing des outils MCP impossible pour '{}' : {}", connectionName(client), ex.getMessage());
            return List.of();
        }
    }

    private static McpResourceContent toContent(McpSchema.ResourceContents contents) {
        return switch (contents) {
            case McpSchema.TextResourceContents text ->
                    new McpResourceContent(text.uri(), text.mimeType(), text.text(), null);
            case McpSchema.BlobResourceContents blob ->
                    new McpResourceContent(blob.uri(), blob.mimeType(), null, blob.blob());
            default -> new McpResourceContent(contents.uri(), contents.mimeType(), null, null);
        };
    }

    private static boolean supportsResources(McpSyncClient client) {
        return client.getServerCapabilities() != null && client.getServerCapabilities().resources() != null;
    }

    private static List<String> textOf(List<McpSchema.Content> content) {
        if (content == null) {
            return List.of();
        }
        return content.stream()
                .map(part -> part instanceof McpSchema.TextContent text ? text.text() : "[" + part.type() + "]")
                .toList();
    }
}
