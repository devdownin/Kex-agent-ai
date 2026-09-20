// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.isolation.IsolatedStdioCommand;
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
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/** Catalogue des serveurs MCP déclarés au démarrage et administrés à chaud depuis la console. */
public class McpToolCatalog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpToolCatalog.class);
    private static final String CLIENT_NAME_SEPARATOR = " - ";
    private static final String CIRCUIT_BREAKER_PREFIX = "mcp-tool-";
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("host", "content-length", "connection");

    private final List<McpSyncClient> clients;
    private final Set<String> staticConnections;
    private final Map<String, McpSyncClient> dynamicClients = new ConcurrentHashMap<>();
    private final Map<String, McpServerRegistration> definitions = new ConcurrentHashMap<>();
    private final Map<String, Instant> secretRotations = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<McpHealthSample>> healthHistory = new ConcurrentHashMap<>();
    private final Map<String, Map<String, McpToolInfo>> toolSnapshots = new ConcurrentHashMap<>();
    private final Map<String, McpToolDiff> toolDiffs = new ConcurrentHashMap<>();
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final Map<String, CircuitBreaker> circuitBreakers;
    private final Retry retry;
    private final ObjectMapper objectMapper;
    private final McpRuntimeProperties properties;
    private final EncryptedMcpServerStore store;
    private final Map<McpSyncClient, IsolatedStdioCommand> isolatedProcesses = new ConcurrentHashMap<>();

    public McpToolCatalog(List<McpSyncClient> clients, ObservationRegistry observationRegistry,
                          CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry,
                          MeterRegistry meterRegistry) {
        this(clients, observationRegistry, circuitBreakerRegistry, retryRegistry, meterRegistry,
                new ObjectMapper(), new McpRuntimeProperties(".kex/mcp-servers.enc", "",
                        Duration.ofSeconds(30), 50, List.of()));
    }

    public McpToolCatalog(List<McpSyncClient> clients, ObservationRegistry observationRegistry,
                          CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry,
                          MeterRegistry meterRegistry, ObjectMapper objectMapper,
                          McpRuntimeProperties properties) {
        this.clients = new CopyOnWriteArrayList<>(clients);
        this.staticConnections = clients.stream().map(McpToolCatalog::connectionName).collect(Collectors.toSet());
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.retry = retryRegistry.retry("mcp-tool");
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.store = new EncryptedMcpServerStore(objectMapper, properties);
        this.circuitBreakers = new ConcurrentHashMap<>();

        clients.forEach(client -> registerInfrastructure(connectionName(client), client, true));
        restore();
    }

    public List<McpServerInfo> servers() {
        initializePending();
        return clients.stream().map(this::describe).toList();
    }

    /** Le client créé ici est déjà le test de connexion : rien n'est enregistré avant le handshake. */
    public synchronized McpServerInfo register(McpServerRegistration registration) {
        McpServerRegistration normalized = normalize(registration);
        assertConnectionAvailable(normalized.connection());
        long started = System.nanoTime();
        McpSyncClient candidate = createClient(normalized);
        try {
            candidate.initialize();
            McpServerInfo info = describeCandidate(normalized.connection(), candidate);
            recordHealth(normalized.connection(), true, elapsed(started), "Handshake et catalogue disponibles");
            definitions.put(normalized.connection(), normalized);
            secretRotations.put(normalized.connection(), Instant.now());
            try {
                persist();
            }
            catch (RuntimeException ex) {
                definitions.remove(normalized.connection());
                secretRotations.remove(normalized.connection());
                throw ex;
            }
            if (normalized.enabled()) install(normalized.connection(), candidate);
            else closeClient(candidate);
            return info;
        }
        catch (RuntimeException ex) {
            if (!dynamicClients.containsKey(normalized.connection())) closeClient(candidate);
            recordHealth(normalized.connection(), false, elapsed(started), diagnosticMessage(ex));
            if (ex instanceof McpStorageException) throw ex;
            throw new McpServerUnavailableException(normalized.connection(), ex);
        }
    }

    public McpConnectionTestResult test(McpServerRegistration registration) {
        McpServerRegistration normalized = withStoredSecrets(normalize(registration));
        long started = System.nanoTime();
        McpSyncClient candidate = createClient(normalized);
        try {
            McpSchema.InitializeResult result = candidate.initialize();
            McpSchema.Implementation info = candidate.getServerInfo();
            List<String> tools = listTools(candidate).stream().map(McpToolInfo::name).toList();
            long latency = elapsed(started);
            recordHealth(normalized.connection(), true, latency, "Connexion testée avec succès");
            return new McpConnectionTestResult(true, info == null ? null : info.name(),
                    info == null ? null : info.version(), result == null ? null : result.protocolVersion(),
                    latency, tools, tools.size() + " outil(s) découvert(s)");
        }
        catch (RuntimeException ex) {
            long latency = elapsed(started);
            recordHealth(normalized.connection(), false, latency, diagnosticMessage(ex));
            return new McpConnectionTestResult(false, null, null, null, latency, List.of(),
                    diagnosticMessage(ex));
        }
        finally {
            closeClient(candidate);
        }
    }

    public synchronized McpRuntimeServerView update(String connection, McpServerRegistration registration) {
        McpServerRegistration previous = requireDynamic(connection);
        if (!connection.equals(registration.connection())) {
            throw new IllegalArgumentException("Le nom de connexion ne peut pas être modifié");
        }
        McpServerRegistration normalized = withStoredSecrets(normalize(registration));
        McpSyncClient candidate = createClient(normalized);
        long started = System.nanoTime();
        try {
            candidate.initialize();
            recordHealth(connection, true, elapsed(started), "Configuration mise à jour et testée");
            definitions.put(connection, normalized);
            try {
                persist();
            }
            catch (RuntimeException ex) {
                definitions.put(connection, previous);
                throw ex;
            }
            deactivate(connection);
            if (normalized.enabled()) install(connection, candidate);
            else closeClient(candidate);
            return runtimeView(connection);
        }
        catch (RuntimeException ex) {
            if (!dynamicClients.containsValue(candidate)) closeClient(candidate);
            recordHealth(connection, false, elapsed(started), diagnosticMessage(ex));
            if (ex instanceof McpStorageException) throw ex;
            throw new McpServerUnavailableException(connection, ex);
        }
    }

    public synchronized McpRuntimeServerView setEnabled(String connection, boolean enabled) {
        McpServerRegistration current = requireDynamic(connection);
        if (current.enabled() == enabled) return runtimeView(connection);
        McpServerRegistration changed = copyWith(current, enabled, current.bearerToken(), current.headers(),
                current.environment());
        McpSyncClient candidate = null;
        if (enabled) {
            candidate = createClient(changed);
            try {
                candidate.initialize();
            }
            catch (RuntimeException ex) {
                closeClient(candidate);
                throw new McpServerUnavailableException(connection, ex);
            }
        }
        definitions.put(connection, changed);
        try {
            persist();
        }
        catch (RuntimeException ex) {
            definitions.put(connection, current);
            if (candidate != null) closeClient(candidate);
            throw ex;
        }
        if (enabled) install(connection, candidate);
        else deactivate(connection);
        return runtimeView(connection);
    }

    public synchronized McpRuntimeServerView rotateSecret(String connection, McpSecretRotation rotation) {
        McpServerRegistration current = requireDynamic(connection);
        Map<String, String> headers = rotation.headers().isEmpty() ? current.headers() : rotation.headers();
        Map<String, String> environment = rotation.environment().isEmpty()
                ? current.environment() : rotation.environment();
        String bearer = rotation.bearerToken() == null ? current.bearerToken() : rotation.bearerToken();
        McpServerRegistration changed = copyWith(current, current.enabled(), bearer, headers, environment);
        McpSyncClient candidate = createClient(changed);
        long started = System.nanoTime();
        Instant previousRotation = secretRotations.get(connection);
        try {
            candidate.initialize();
            recordHealth(connection, true, elapsed(started), "Nouveaux secrets validés");
            Instant rotatedAt = Instant.now();
            definitions.put(connection, changed);
            secretRotations.put(connection, rotatedAt);
            try {
                persist();
            }
            catch (RuntimeException ex) {
                definitions.put(connection, current);
                if (previousRotation == null) secretRotations.remove(connection);
                else secretRotations.put(connection, previousRotation);
                throw ex;
            }
            if (changed.enabled()) {
                deactivate(connection);
                install(connection, candidate);
            }
            else closeClient(candidate);
        }
        catch (RuntimeException ex) {
            closeClient(candidate);
            recordHealth(connection, false, elapsed(started), diagnosticMessage(ex));
            throw new McpServerUnavailableException(connection, ex);
        }
        return runtimeView(connection);
    }

    public synchronized void unregister(String connection) {
        McpServerRegistration previous = requireDynamic(connection);
        Instant previousRotation = secretRotations.remove(connection);
        definitions.remove(connection);
        try {
            persist();
        }
        catch (RuntimeException ex) {
            definitions.put(connection, previous);
            if (previousRotation != null) secretRotations.put(connection, previousRotation);
            throw ex;
        }
        deactivate(connection);
        healthHistory.remove(connection);
        toolSnapshots.remove(connection);
        toolDiffs.remove(connection);
        circuitBreakers.remove(connection);
    }

    public List<McpRuntimeServerView> runtimeServers() {
        return definitions.keySet().stream().sorted().map(this::runtimeView).toList();
    }

    /** Conservé pour les clients de la première API runtime. */
    public List<String> dynamicConnectionNames() {
        return definitions.keySet().stream().sorted().toList();
    }

    public List<McpServerInfo> dynamicServers() {
        return dynamicClients.entrySet().stream()
                .filter(entry -> definitions.get(entry.getKey()).enabled())
                .map(entry -> describe(entry.getValue()))
                .toList();
    }

    public synchronized McpServerDiagnostics refresh(String connection) {
        McpServerRegistration definition = requireDynamic(connection);
        long started = System.nanoTime();
        if (!definition.enabled()) {
            McpConnectionTestResult result = test(definition);
            if (!result.success()) {
                throw new McpServerUnavailableException(connection, new IllegalStateException(result.message()));
            }
            return diagnostics(connection);
        }
        McpSyncClient client = dynamicClients.get(connection);
        try {
            List<McpToolInfo> tools = listToolsStrict(client);
            Instant comparedAt = Instant.now();
            Map<String, McpToolInfo> previous = toolSnapshots.getOrDefault(connection, Map.of());
            Map<String, McpToolInfo> current = indexTools(tools);
            toolDiffs.put(connection, compareTools(previous, current, comparedAt));
            toolSnapshots.put(connection, current);
            recordHealth(connection, true, elapsed(started), "Catalogue d'outils rafraîchi");
        }
        catch (RuntimeException ex) {
            recordHealth(connection, false, elapsed(started), diagnosticMessage(ex));
            throw new McpServerUnavailableException(connection, ex);
        }
        return diagnostics(connection);
    }

    public McpServerDiagnostics diagnostics(String connection) {
        McpServerRegistration definition = requireDynamic(connection);
        McpSyncClient client = dynamicClients.get(connection);
        int toolCount = client == null ? 0 : listTools(client).size();
        return new McpServerDiagnostics(connection, definition.transport(), definition.enabled(),
                client != null && client.isInitialized(), toolCount,
                toolDiffs.getOrDefault(connection, McpToolDiff.empty(Instant.now())), conflictsFor(connection),
                definition.capabilityMappings(), history(connection));
    }

    public McpConfigurationBundle exportConfiguration() {
        return new McpConfigurationBundle(1, definitions.values().stream()
                .sorted(Comparator.comparing(McpServerRegistration::connection))
                .map(McpServerRegistration::withoutSecrets)
                .toList());
    }

    public synchronized List<McpRuntimeServerView> importConfiguration(McpConfigurationBundle bundle) {
        if (bundle.version() != 1) throw new IllegalArgumentException("Version d'import MCP non prise en charge");
        Set<String> incoming = new HashSet<>();
        for (McpServerRegistration registration : bundle.servers()) {
            McpServerRegistration normalized = normalize(registration);
            if (!incoming.add(normalized.connection()) || staticConnections.contains(normalized.connection())
                    || definitions.containsKey(normalized.connection())) {
                throw new IllegalArgumentException("Conflit de connexion MCP : " + normalized.connection());
            }
        }
        List<String> added = new ArrayList<>();
        bundle.servers().forEach(registration -> {
            McpServerRegistration normalized = normalize(registration);
            definitions.put(normalized.connection(), copyWith(normalized, false, null,
                    nonBlankEntries(normalized.headers()), nonBlankEntries(normalized.environment())));
            added.add(normalized.connection());
        });
        try {
            persist();
        }
        catch (RuntimeException ex) {
            added.forEach(definitions::remove);
            throw ex;
        }
        return runtimeServers();
    }

    public Map<String, Object> storageStatus() {
        return Map.of("encryptedPersistence", store.enabled(), "configuredServers", definitions.size());
    }

    public boolean isToolAllowed(String connection, String tool) {
        McpServerRegistration definition = definitions.get(connection);
        return definition == null || definition.allowedTools().isEmpty() || definition.allowedTools().contains(tool);
    }

    public String capability(String connection, String tool) {
        McpServerRegistration definition = definitions.get(connection);
        return definition == null ? null : definition.capabilityMappings().get(tool);
    }

    /** Invocation directe avec permission, observation, réessai et disjoncteur par connexion. */
    public McpToolResult call(String connection, String tool, Map<String, Object> arguments) {
        if (!isToolAllowed(connection, tool)) throw new McpToolForbiddenException(connection, tool);
        CircuitBreaker circuitBreaker = circuitBreakerFor(connection);
        Supplier<McpToolResult> invocation = () -> Observation.createNotStarted("kex.mcp.tool.call", observationRegistry)
                .lowCardinalityKeyValue("connection", connection)
                .lowCardinalityKeyValue("tool", tool)
                .observe(() -> {
                    McpSchema.CallToolResult result = invokeTool(connection, tool, arguments);
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

    public List<McpResourceInfo> resources(String connection) {
        McpSyncClient client = client(connection);
        if (!supportsResources(client)) return List.of();
        return client.listResources().resources().stream()
                .map(resource -> new McpResourceInfo(resource.uri(), resource.name(), resource.description(),
                        resource.mimeType(), resource.size()))
                .toList();
    }

    public List<McpResourceContent> readResource(String connection, String uri) {
        McpSyncClient client = client(connection);
        if (!supportsResources(client)) throw new UnsupportedMcpCapabilityException(connection, "resources");
        return client.readResource(new McpSchema.ReadResourceRequest(uri)).contents().stream()
                .map(McpToolCatalog::toContent)
                .toList();
    }

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

    @Override
    public synchronized void close() {
        dynamicClients.values().forEach(this::closeClient);
        clients.removeAll(dynamicClients.values());
        dynamicClients.clear();
    }

    private void restore() {
        if (!store.enabled()) {
            log.info("Persistance MCP runtime désactivée : définir KEX_MCP_STORAGE_KEY pour l'activer");
            return;
        }
        for (EncryptedMcpServerStore.PersistedServer persisted : store.load()) {
            McpServerRegistration registration = normalize(persisted.registration());
            if (staticConnections.contains(registration.connection()) || definitions.putIfAbsent(
                    registration.connection(), registration) != null) {
                log.warn("Connexion MCP persistée '{}' ignorée : nom déjà utilisé", registration.connection());
                continue;
            }
            if (persisted.secretRotatedAt() != null) {
                secretRotations.put(registration.connection(), persisted.secretRotatedAt());
            }
            if (registration.enabled()) {
                try {
                    activate(registration);
                }
                catch (RuntimeException ex) {
                    recordHealth(registration.connection(), false, null, diagnosticMessage(ex));
                    log.warn("Serveur MCP persisté '{}' indisponible au démarrage", registration.connection());
                }
            }
        }
    }

    private void persist() {
        store.save(definitions.values().stream()
                .sorted(Comparator.comparing(McpServerRegistration::connection))
                .map(registration -> new EncryptedMcpServerStore.PersistedServer(registration,
                        secretRotations.get(registration.connection())))
                .toList());
    }

    private void activate(McpServerRegistration registration) {
        long started = System.nanoTime();
        McpSyncClient client = createClient(registration);
        try {
            client.initialize();
            install(registration.connection(), client);
            recordHealth(registration.connection(), true, elapsed(started), "Connexion activée");
        }
        catch (RuntimeException ex) {
            closeClient(client);
            recordHealth(registration.connection(), false, elapsed(started), diagnosticMessage(ex));
            throw new McpServerUnavailableException(registration.connection(), ex);
        }
    }

    private void install(String connection, McpSyncClient client) {
        clients.add(client);
        dynamicClients.put(connection, client);
        toolSnapshots.putIfAbsent(connection, indexTools(listTools(client)));
        toolDiffs.putIfAbsent(connection, McpToolDiff.empty(Instant.now()));
        registerInfrastructure(connection, client, false);
    }

    private void deactivate(String connection) {
        McpSyncClient active = dynamicClients.remove(connection);
        if (active != null) {
            clients.remove(active);
            closeClient(active);
        }
    }

    private McpSchema.CallToolResult invokeTool(String connection, String tool, Map<String, Object> arguments) {
        McpServerRegistration registration = definitions.get(connection);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(tool,
                arguments == null ? Map.of() : arguments);
        if (!isolated(registration)) return client(connection).callTool(request);
        if (!registration.enabled()) throw new UnknownMcpServerException(connection);
        // Discovery is persistent; tool execution always receives a new filesystem and process.
        McpSyncClient taskClient = createClient(registration);
        try {
            taskClient.initialize();
            return taskClient.callTool(request);
        }
        finally {
            closeClient(taskClient);
        }
    }

    private boolean isolated(McpServerRegistration registration) {
        return registration != null && "STDIO".equals(registration.transport()) && properties.isolation().enabled();
    }

    private void closeClient(McpSyncClient client) {
        IsolatedStdioCommand process = isolatedProcesses.remove(client);
        try {
            client.close();
        }
        finally {
            if (process != null) process.close();
        }
    }

    private McpSyncClient createClient(McpServerRegistration registration) {
        IsolatedStdioCommand process = isolated(registration)
                ? new IsolatedStdioCommand(properties.isolation(), registration.command(), registration.args(),
                        registration.environment()) : null;
        try {
            McpClientTransport transport = "STDIO".equals(registration.transport())
                    ? stdioTransport(registration, process) : httpTransport(registration);
            McpSyncClient client = McpClient.sync(transport)
                    .clientInfo(new McpSchema.Implementation("kex-agent - " + registration.connection(), "runtime"))
                    .requestTimeout(properties.requestTimeout()).build();
            if (process != null) isolatedProcesses.put(client, process);
            return client;
        }
        catch (RuntimeException ex) {
            if (process != null) process.close();
            throw ex;
        }
    }

    private McpClientTransport httpTransport(McpServerRegistration registration) {
        URI base = validatedBaseUrl(registration.url());
        String endpoint = StringUtils.hasText(registration.endpoint()) ? registration.endpoint().trim() : "/mcp";
        if (!endpoint.startsWith("/") || endpoint.contains("?") || endpoint.contains("#")) {
            throw new IllegalArgumentException("L'endpoint MCP doit être un chemin absolu sans query ni fragment");
        }
        validateHeaders(registration.headers());
        var builder = HttpClientStreamableHttpTransport.builder(base.toString()).endpoint(endpoint);
        if (StringUtils.hasText(registration.bearerToken()) || !registration.headers().isEmpty()) {
            builder.asyncHttpRequestCustomizer(McpAsyncHttpClientRequestCustomizer.fromSync(
                    (request, method, uri, body, context) -> {
                        registration.headers().forEach(request::header);
                        if (StringUtils.hasText(registration.bearerToken())) {
                            request.header("Authorization", "Bearer " + registration.bearerToken());
                        }
                    }));
        }
        return builder.build();
    }

    /** Réflexion limitée à la fabrique stdio pour rester compatible avec les mappers SDK 0.x/1.x. */
    private McpClientTransport stdioTransport(McpServerRegistration registration, IsolatedStdioCommand process) {
        if (!StringUtils.hasText(registration.command())) {
            throw new IllegalArgumentException("La commande du transport STDIO est obligatoire");
        }
        try {
            Class<?> parametersType = Class.forName("io.modelcontextprotocol.client.transport.ServerParameters");
            Object builder = parametersType.getMethod("builder", String.class)
                    .invoke(null, process == null ? registration.command().trim() : process.executable());
            builder.getClass().getMethod("args", List.class).invoke(builder, process == null ? registration.args() : process.arguments());
            builder.getClass().getMethod("env", Map.class).invoke(builder, process == null ? registration.environment() : Map.of());
            Object parameters = builder.getClass().getMethod("build").invoke(builder);
            Class<?> transportType = Class.forName("io.modelcontextprotocol.client.transport.StdioClientTransport");
            for (Constructor<?> constructor : transportType.getConstructors()) {
                Object[] arguments = stdioArguments(constructor.getParameterTypes(), parameters);
                if (arguments != null) return (McpClientTransport) constructor.newInstance(arguments);
            }
            throw new IllegalStateException("Aucun constructeur STDIO MCP compatible");
        }
        catch (ClassNotFoundException | IllegalAccessException | InvocationTargetException
                | InstantiationException | NoSuchMethodException ex) {
            throw new IllegalArgumentException("Transport STDIO MCP indisponible", ex);
        }
    }

    private Object[] stdioArguments(Class<?>[] types, Object parameters) {
        if (types.length == 1 && types[0].isInstance(parameters)) return new Object[] { parameters };
        if (types.length != 2 || !types[0].isInstance(parameters)) return null;
        if (types[1].isInstance(objectMapper)) return new Object[] { parameters, objectMapper };
        try {
            Class<?> defaults = Class.forName("io.modelcontextprotocol.json.McpJsonDefaults");
            Object mapper = defaults.getMethod("getMapper").invoke(null);
            return types[1].isInstance(mapper) ? new Object[] { parameters, mapper } : null;
        }
        catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private McpServerRegistration normalize(McpServerRegistration source) {
        String connection = source.connection().trim();
        String transport = source.transport().toUpperCase();
        if (!Set.of("HTTP", "STDIO").contains(transport)) {
            throw new IllegalArgumentException("Transport MCP inconnu : " + transport);
        }
        if ("HTTP".equals(transport)) validatedBaseUrl(source.url());
        if ("STDIO".equals(transport) && !StringUtils.hasText(source.command())) {
            throw new IllegalArgumentException("La commande STDIO est obligatoire");
        }
        if ("STDIO".equals(transport) && properties.allowedStdioCommands().stream()
                .noneMatch(command -> command.equals(source.command().trim()))) {
            throw new IllegalArgumentException("Commande STDIO non autorisée ; configurez "
                    + "KEX_MCP_STDIO_ALLOWED_COMMANDS");
        }
        if ("STDIO".equals(transport) && properties.isolation().enabled()
                && !properties.isolation().images().containsKey(source.command().trim())) {
            throw new IllegalArgumentException("Aucune image d'isolation approuvée pour cette commande STDIO");
        }
        if (StringUtils.hasText(source.bearerToken()) && source.headers().keySet().stream()
                .anyMatch(name -> "authorization".equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("Choisissez soit le bearer, soit l'en-tête Authorization");
        }
        validateHeaders(source.headers());
        return new McpServerRegistration(connection, transport, trimToNull(source.url()),
                StringUtils.hasText(source.endpoint()) ? source.endpoint().trim() : "/mcp",
                source.bearerToken(), source.headers(), trimToNull(source.command()), source.args(),
                source.environment(), source.enabled(), source.allowedTools(), source.capabilityMappings());
    }

    private McpServerRegistration copyWith(McpServerRegistration source, boolean enabled, String bearer,
                                           Map<String, String> headers, Map<String, String> environment) {
        return new McpServerRegistration(source.connection(), source.transport(), source.url(), source.endpoint(),
                bearer, headers, source.command(), source.args(), environment, enabled,
                source.allowedTools(), source.capabilityMappings());
    }

    /** L'UI ne relit jamais un secret : un champ laissé vide lors d'une édition conserve l'ancien. */
    private McpServerRegistration withStoredSecrets(McpServerRegistration incoming) {
        McpServerRegistration current = definitions.get(incoming.connection());
        if (current == null) return incoming;
        Map<String, String> headers = nonBlankEntries(incoming.headers());
        Map<String, String> environment = nonBlankEntries(incoming.environment());
        return copyWith(incoming, incoming.enabled(), StringUtils.hasText(incoming.bearerToken())
                        ? incoming.bearerToken() : current.bearerToken(),
                headers.isEmpty() ? current.headers() : headers,
                environment.isEmpty() ? current.environment() : environment);
    }

    private McpServerRegistration requireDynamic(String connection) {
        McpServerRegistration definition = definitions.get(connection);
        if (definition == null) throw new UnknownMcpServerException(connection);
        return definition;
    }

    private void assertConnectionAvailable(String connection) {
        if (staticConnections.contains(connection) || definitions.containsKey(connection)) {
            throw new IllegalArgumentException("Une connexion MCP nommée '" + connection + "' existe déjà");
        }
    }

    private McpRuntimeServerView runtimeView(String connection) {
        McpServerRegistration registration = requireDynamic(connection);
        return new McpRuntimeServerView(connection, registration.transport(), registration.url(),
                registration.endpoint(), registration.command(), registration.args(), registration.headers().keySet(),
                registration.environment().keySet(), registration.enabled(), registration.allowedTools(),
                registration.capabilityMappings(), StringUtils.hasText(registration.bearerToken()),
                secretRotations.get(connection));
    }

    private List<String> conflictsFor(String connection) {
        List<String> conflicts = new ArrayList<>();
        McpSyncClient target = dynamicClients.get(connection);
        Set<String> targetTools = target == null ? Set.of() : listTools(target).stream()
                .map(McpToolInfo::name).collect(Collectors.toSet());
        dynamicClients.forEach((otherConnection, otherClient) -> {
            if (!connection.equals(otherConnection)) {
                listTools(otherClient).stream().map(McpToolInfo::name).filter(targetTools::contains)
                        .forEach(tool -> conflicts.add("Outil '" + tool + "' aussi exposé par " + otherConnection));
                if (callbackPrefix(connection).equals(callbackPrefix(otherConnection))) {
                    conflicts.add("Préfixe d'outil identique à " + otherConnection);
                }
            }
        });
        McpServerRegistration definition = definitions.get(connection);
        definition.allowedTools().stream().filter(tool -> !targetTools.contains(tool))
                .forEach(tool -> conflicts.add("Permission vers un outil absent : " + tool));
        definition.capabilityMappings().keySet().stream().filter(tool -> !targetTools.contains(tool))
                .forEach(tool -> conflicts.add("Capacité liée à un outil absent : " + tool));
        return conflicts.stream().distinct().sorted().toList();
    }

    static String callbackPrefix(String connection) {
        return connection.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private List<McpHealthSample> history(String connection) {
        ArrayDeque<McpHealthSample> samples = healthHistory.get(connection);
        if (samples == null) return List.of();
        synchronized (samples) {
            return List.copyOf(samples);
        }
    }

    private void recordHealth(String connection, boolean healthy, Long latency, String message) {
        ArrayDeque<McpHealthSample> samples = healthHistory.computeIfAbsent(connection, ignored -> new ArrayDeque<>());
        synchronized (samples) {
            samples.addFirst(new McpHealthSample(Instant.now(), healthy, latency, message));
            while (samples.size() > properties.healthHistorySize()) samples.removeLast();
        }
    }

    private void registerInfrastructure(String connection, McpSyncClient client, boolean gauge) {
        circuitBreakers.put(connection,
                circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_PREFIX + connection));
        if (gauge) {
            Gauge.builder("kex.mcp.server.up", client, McpToolCatalog::upValue)
                    .description("1 si le client MCP est initialisé, 0 sinon")
                    .tag("connection", connection)
                    .register(meterRegistry);
        }
    }

    private CircuitBreaker circuitBreakerFor(String connection) {
        CircuitBreaker breaker = circuitBreakers.get(connection);
        if (breaker == null) throw new UnknownMcpServerException(connection);
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
        return clients.stream().filter(candidate -> connection.equals(connectionName(candidate))).findFirst();
    }

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
        return describeCandidate(connectionName(client), client);
    }

    private McpServerInfo describeCandidate(String connection, McpSyncClient client) {
        McpSchema.Implementation info = client.getServerInfo();
        McpSchema.InitializeResult initialization = client.getCurrentInitializationResult();
        CircuitBreaker breaker = circuitBreakers.get(connection);
        return new McpServerInfo(connection, info != null ? info.name() : null,
                info != null ? info.version() : null,
                initialization != null ? initialization.protocolVersion() : null,
                client.isInitialized(), breaker == null ? null : breaker.getState().name(),
                listTools(client).stream().filter(tool -> isToolAllowed(connection, tool.name())).toList());
    }

    private static String connectionName(McpSyncClient client) {
        McpSchema.Implementation clientInfo = client.getClientInfo();
        if (clientInfo == null || clientInfo.name() == null) return "unknown";
        int separator = clientInfo.name().lastIndexOf(CLIENT_NAME_SEPARATOR);
        return separator < 0 ? clientInfo.name() : clientInfo.name().substring(separator + CLIENT_NAME_SEPARATOR.length());
    }

    private static List<McpToolInfo> listTools(McpSyncClient client) {
        if (client == null || !client.isInitialized()) return List.of();
        try {
            return listToolsStrict(client);
        }
        catch (RuntimeException ex) {
            log.warn("Listing des outils MCP impossible pour '{}' : {}", connectionName(client), ex.getMessage());
            return List.of();
        }
    }

    private static List<McpToolInfo> listToolsStrict(McpSyncClient client) {
        if (client == null || !client.isInitialized()) return List.of();
        return client.listTools().tools().stream()
                .map(tool -> new McpToolInfo(tool.name(), tool.description(), tool.inputSchema()))
                .toList();
    }

    private static Map<String, McpToolInfo> indexTools(List<McpToolInfo> tools) {
        return tools.stream().collect(Collectors.toMap(McpToolInfo::name, tool -> tool, (first, ignored) -> first));
    }

    static McpToolDiff compareTools(Map<String, McpToolInfo> previous, Map<String, McpToolInfo> current,
                                    Instant comparedAt) {
        List<String> added = current.keySet().stream().filter(name -> !previous.containsKey(name)).sorted().toList();
        List<String> removed = previous.keySet().stream().filter(name -> !current.containsKey(name)).sorted().toList();
        List<String> schemaChanged = current.entrySet().stream()
                .filter(entry -> previous.containsKey(entry.getKey()))
                .filter(entry -> !Objects.equals(previous.get(entry.getKey()).inputSchema(),
                        entry.getValue().inputSchema()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        return new McpToolDiff(comparedAt, added, removed, schemaChanged);
    }

    private static URI validatedBaseUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim()).normalize();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
            return uri;
        }
        catch (RuntimeException ex) {
            throw new IllegalArgumentException("URL MCP HTTP(S) absolue invalide");
        }
    }

    private static void validateHeaders(Map<String, String> headers) {
        headers.forEach((name, value) -> {
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                    || FORBIDDEN_HEADERS.contains(name.toLowerCase())) {
                throw new IllegalArgumentException("En-tête HTTP MCP interdit : " + name);
            }
            if (value.contains("\r") || value.contains("\n")) {
                throw new IllegalArgumentException("Valeur d'en-tête HTTP MCP invalide : " + name);
            }
        });
    }

    private static Map<String, String> nonBlankEntries(Map<String, String> source) {
        return source.entrySet().stream().filter(entry -> StringUtils.hasText(entry.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static String diagnosticMessage(RuntimeException ex) {
        return "Échec " + ex.getClass().getSimpleName();
    }

    private static String tagOrUnknown(Timer timer, String tag) {
        String value = timer.getId().getTag(tag);
        return value == null ? "unknown" : value;
    }

    private static double upValue(McpSyncClient client) {
        return client.isInitialized() ? 1.0 : 0.0;
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
        if (content == null) return List.of();
        return content.stream()
                .map(part -> part instanceof McpSchema.TextContent text ? text.text() : "[" + part.type() + "]")
                .toList();
    }
}
