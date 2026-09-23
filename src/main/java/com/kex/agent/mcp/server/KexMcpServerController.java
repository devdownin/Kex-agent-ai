// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.kafka.KafkaViewService;
import com.kex.agent.supervision.SupervisionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stateless MCP Streamable HTTP with JSON responses. No sessions, SSE, sampling or human-approval
 * tools are advertised. Every request authenticates independently using the existing API keys.
 */
@RestController
@RequestMapping("/api/agent/mcp-server")
@ConditionalOnProperty(prefix = "kex.mcp.server", name = "enabled", havingValue = "true")
public class KexMcpServerController {

    private static final String PROTOCOL = "2025-06-18";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final Set<String> PROTOCOLS = Set.of(PROTOCOL, "2025-03-26");
    private static final int MAX_REQUEST_LENGTH = 65_536;
    private static final Map<String, Object> EMPTY_SCHEMA = Map.of(
            "type", "object", "properties", Map.of(), "additionalProperties", false);
    // Phase 1 deliberately exposes observation only. Mutating supervision operations stay behind
    // Kex's operator API/console until MCP-specific approval and audit semantics are defined.
    private static final List<Map<String, Object>> PROMPTS = List.of(
            Map.of("name", "kex_supervision_triage", "title", "Kex supervision triage",
                    "description", "Guide a read-only investigation using Kex supervision tools and resources."));
    private static final Map<String, Object> STATUS_SCHEMA = McpServerCatalog.objectSchema(Map.of(
            "state", McpServerCatalog.stringSchema(), "mode", McpServerCatalog.stringSchema(), "paused", McpServerCatalog.booleanSchema(),
            "analysing", McpServerCatalog.booleanSchema(), "confidenceThreshold", McpServerCatalog.numberSchema(),
            "circuitBreakers", McpServerCatalog.arraySchema(McpServerCatalog.objectSchema())));
    private static final Map<String, Object> OVERVIEW_SCHEMA = McpServerCatalog.objectSchema(McpServerCatalog.schema(
            "agent", STATUS_SCHEMA, "processesMonitored", McpServerCatalog.integerSchema(), "processesOk", McpServerCatalog.integerSchema(),
            "processesWarning", McpServerCatalog.integerSchema(), "processesError", McpServerCatalog.integerSchema(),
            "processesUnknown", McpServerCatalog.integerSchema(), "anomaliesDetected", McpServerCatalog.integerSchema(),
            "pendingApprovals", McpServerCatalog.integerSchema(), "processes", McpServerCatalog.arraySchema(McpServerCatalog.objectSchema()),
            "alerts", McpServerCatalog.arraySchema(McpServerCatalog.objectSchema()), "pending", McpServerCatalog.arraySchema(McpServerCatalog.objectSchema()),
            "maintenance", McpServerCatalog.arraySchema(McpServerCatalog.objectSchema()), "incidents", McpServerCatalog.arraySchema(McpServerCatalog.objectSchema())));
    private static final Map<String, Object> ALERTS_SCHEMA = McpServerCatalog.objectSchema(Map.of(
            "alerts", McpServerCatalog.arraySchema(McpServerCatalog.objectSchema(Map.of(
                    "id", McpServerCatalog.stringSchema(), "processId", McpServerCatalog.stringSchema(), "title", McpServerCatalog.stringSchema(),
                    "severity", McpServerCatalog.stringSchema(), "occurrences", McpServerCatalog.integerSchema(), "confidence", McpServerCatalog.numberSchema())))));
    private static final Map<String, Object> INCIDENTS_SCHEMA = McpServerCatalog.objectSchema(Map.of(
            "incidents", McpServerCatalog.arraySchema(McpServerCatalog.objectSchema(Map.of(
                    "cycleId", McpServerCatalog.stringSchema(), "processCount", McpServerCatalog.integerSchema(), "severity", McpServerCatalog.stringSchema(),
                    "processNames", McpServerCatalog.arraySchema(McpServerCatalog.stringSchema()), "titles", McpServerCatalog.arraySchema(McpServerCatalog.stringSchema()),
                    "alertIds", McpServerCatalog.arraySchema(McpServerCatalog.stringSchema()))))));
    private static final Map<String, Object> DECISIONS_SCHEMA = McpServerCatalog.objectSchema(Map.of(
            "decisions", McpServerCatalog.arraySchema(McpServerCatalog.objectSchema(Map.of(
                    "id", McpServerCatalog.stringSchema(), "processId", McpServerCatalog.stringSchema(), "capability", McpServerCatalog.stringSchema(),
                    "objective", McpServerCatalog.stringSchema(), "action", McpServerCatalog.stringSchema(), "confidence", McpServerCatalog.numberSchema(),
                    "status", McpServerCatalog.stringSchema(), "correlationId", McpServerCatalog.stringSchema())))));

    private static final List<Map<String, Object>> TOOLS = List.of(
            McpServerCatalog.tool("kex_status", "Read Kex supervision status.", STATUS_SCHEMA),
            McpServerCatalog.tool("kex_overview", "Read the current supervision overview, including process states and counts.",
                    OVERVIEW_SCHEMA),
            McpServerCatalog.tool("kex_alerts", "Read the currently active supervision alerts.", ALERTS_SCHEMA),
            McpServerCatalog.tool("kex_incidents", "Read incidents correlated from the latest supervision cycle.", INCIDENTS_SCHEMA),
            McpServerCatalog.tool("kex_pending_decisions", "Read decisions awaiting human review. Cannot approve them.",
                    DECISIONS_SCHEMA),
            McpServerCatalog.toolWithInput("kex_diagnose_topic", "Read the current Kafka lag diagnosis for one topic.",
                    Map.of("type", "object", "properties", Map.of("topic", McpServerCatalog.stringSchema()),
                            "required", List.of("topic"), "additionalProperties", false),
                    McpServerCatalog.KAFKA_LAG_SCHEMA),
            McpServerCatalog.toolWithInput("kex_diagnose_process",
                    "Correlate snapshot, alerts and pending decisions for one process.",
                    Map.of("type", "object", "properties", Map.of("processId", McpServerCatalog.stringSchema()),
                            "required", List.of("processId"), "additionalProperties", false),
                    McpServerCatalog.PROCESS_DIAGNOSIS_SCHEMA));

    private final ObjectMapper mapper;
    private final ObjectProvider<SupervisionService> supervision;
    private final ObjectProvider<KafkaViewService> kafka;
    private final KexMcpServerProperties properties;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final ObjectProvider<McpServerAuditPublisher> audit;
    private final ObjectProvider<McpServerRateLimiter> rateLimiter;
    private final MeterRegistry meters;
    private final McpClientSessionRegistry sessions = new McpClientSessionRegistry();

    public KexMcpServerController(ObjectMapper mapper, ObjectProvider<SupervisionService> supervision,
                                  ObjectProvider<KafkaViewService> kafka,
                                  KexMcpServerProperties properties, ObjectProvider<BuildProperties> buildProperties,
                                  ObjectProvider<McpServerAuditPublisher> audit, ObjectProvider<McpServerRateLimiter> rateLimiter,
                                  MeterRegistry meters) {
        this.mapper = mapper;
        this.supervision = supervision;
        this.kafka = kafka;
        this.properties = properties;
        this.buildProperties = buildProperties;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.meters = meters;
    }

    @GetMapping
    ResponseEntity<Object> stream(@RequestHeader HttpHeaders headers, Authentication authentication) {
        ResponseEntity<Object> rejected = authorize(headers, authentication);
        return rejected != null ? rejected : ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(HttpMethod.POST).build();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> receive(@RequestBody String body, @RequestHeader HttpHeaders headers,
                                    Authentication authentication) {
        ResponseEntity<Object> rejected = authorize(headers, authentication);
        if (rejected != null) return recordTransport("authorization", rejected);
        McpServerRateLimiter limiter = rateLimiter.getIfAvailable();
        if (limiter != null && !limiter.allow(authentication.getName())) {
            return recordTransport("rate_limit", ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build());
        }
        if (!accepts(headers, MediaType.APPLICATION_JSON) || !accepts(headers, MediaType.TEXT_EVENT_STREAM)) {
            return recordTransport("accept", ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build());
        }
        if (body.length() > MAX_REQUEST_LENGTH) {
            return recordTransport("payload", ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build());
        }

        JsonNode request;
        try {
            request = mapper.readTree(body);
        }
        catch (JsonProcessingException ex) {
            return error(null, -32700, "Parse error");
        }
        if (request == null || !request.isObject() || !"2.0".equals(request.path("jsonrpc").asText())
                || (request.has("id") && !request.get("id").isTextual() && !request.get("id").isIntegralNumber())) {
            return error(null, -32600, "Invalid Request");
        }
        Object id = request.has("id") ? mapper.convertValue(request.get("id"), Object.class) : null;
        String method = request.path("method").asText("");
        List<String> versions = headers.get("MCP-Protocol-Version");
        if (versions != null && versions.size() != 1) {
            return ResponseEntity.badRequest().body(envelope(id, "error",
                    Map.of("code", -32600, "message", "Exactly one MCP protocol version is required")));
        }
        String version = versions == null ? null : versions.get(0);
        if (version != null && !PROTOCOLS.contains(version)) {
            return ResponseEntity.badRequest().body(envelope(id, "error",
                    Map.of("code", -32600, "message", "Unsupported MCP protocol version")));
        }
        // A server that does not issue client requests has no response to process. Acknowledge
        // valid client responses and notifications without issuing a JSON-RPC response of our own.
        if (!request.has("method")) {
            if (id != null && (request.has("result") ^ request.has("error"))) {
                return ResponseEntity.accepted().build();
            }
            return error(id, -32600, "Invalid Request");
        }
        if (!request.get("method").isTextual() || method.isBlank()
                || (request.has("params") && !request.get("params").isObject())) {
            return error(id, -32600, "Invalid Request");
        }
        if (id == null) {
            // Notifications MUST NOT execute request methods, even when a caller omits the id.
            return method.startsWith("notifications/") ? ResponseEntity.accepted().build()
                    : ResponseEntity.badRequest().build();
        }
        JsonNode params = request.path("params");
        Timer.Sample sample = Timer.start(meters);
        long auditStarted = System.nanoTime();
        ResponseEntity<Object> response = switch (method) {
            case "initialize" -> initialize(id, params);
            case "ping" -> result(id, Map.of());
            case "tools/list" -> params.has("cursor")
                    ? error(id, -32602, "This server does not use pagination cursors")
                    : result(id, Map.of("tools", TOOLS));
            case "tools/call" -> call(id, params);
            case "resources/templates/list" -> params.has("cursor")
                    ? error(id, -32602, "This server does not use pagination cursors")
                    : result(id, Map.of("resourceTemplates", resourceTemplates()));
            case "resources/list" -> params.has("cursor")
                    ? error(id, -32602, "This server does not use pagination cursors")
                    : result(id, Map.of("resources", resources()));
            case "resources/read" -> readResource(id, params);
            case "prompts/list" -> params.has("cursor")
                    ? error(id, -32602, "This server does not use pagination cursors")
                    : result(id, Map.of("prompts", PROMPTS));
            case "prompts/get" -> getPrompt(id, params);
            default -> error(id, -32601, "Method not found");
        };
        if ("initialize".equals(method) && response.getStatusCode().is2xxSuccessful()) {
            String sessionId = sessions.open(params.path("clientInfo").path("name").asText("unknown"),
                    params.path("clientInfo").path("version").asText("unknown"));
            response = ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                    .header(SESSION_HEADER, sessionId).body(response.getBody());
        }
        String outcome = metricOutcome(response);
        sample.stop(meters.timer("kex.mcp.server.request", "method", metricMethod(method), "outcome", outcome));
        publishAudit(authentication, headers, method, params, outcome, System.nanoTime() - auditStarted);
        return response;
    }

    private ResponseEntity<Object> initialize(Object id, JsonNode params) {
        if (!params.path("protocolVersion").isTextual() || !params.path("capabilities").isObject()
                || !params.path("clientInfo").path("name").isTextual()
                || !params.path("clientInfo").path("version").isTextual()) {
            return error(id, -32602, "Invalid initialization parameters");
        }
        String requested = params.path("protocolVersion").asText();
        return result(id, Map.of("protocolVersion", PROTOCOLS.contains(requested) ? requested : PROTOCOL,
                "serverInfo", Map.of("name", "kex-agent-ai", "version", serverVersion()),
                "capabilities", Map.of("tools", Map.of("listChanged", false),
                        "resources", Map.of("listChanged", false), "prompts", Map.of("listChanged", false)),
                "instructions", "Read-only Kex supervision endpoint. Human approvals and all state changes "
                        + "stay in Kex's authenticated operator API and console."));
    }

    private String serverVersion() {
        BuildProperties build = buildProperties.getIfAvailable();
        return build == null || build.getVersion() == null || build.getVersion().isBlank()
                ? "development"
                : build.getVersion();
    }

    private ResponseEntity<Object> call(Object id, JsonNode params) {
        if (!params.path("name").isTextual()) return error(id, -32602, "A textual tool name is required");
        String name = params.path("name").asText("");
        if (TOOLS.stream().noneMatch(tool -> tool.get("name").equals(name))) {
            return error(id, -32602, "Unknown tool");
        }
        JsonNode arguments = params.path("arguments");
        if (params.has("arguments") && !arguments.isObject()) {
            return error(id, -32602, "Tool arguments must be an object");
        }
        if ("kex_diagnose_topic".equals(name)) return diagnoseTopic(id, arguments);
        if ("kex_diagnose_process".equals(name)) return diagnoseProcess(id, arguments);
        if (params.has("arguments") && !arguments.isEmpty()) {
            return error(id, -32602, "This tool accepts an empty arguments object");
        }
        SupervisionService service = supervision.getIfAvailable();
        if (service == null) return toolResult(id, "Supervision is disabled", true);
        try {
            Object value = switch (name) {
                case "kex_status" -> service.status();
                case "kex_overview" -> service.overview();
                case "kex_alerts" -> service.alerts();
                case "kex_incidents" -> service.incidents();
                case "kex_pending_decisions" -> service.pending();
                default -> throw new IllegalStateException("Unreachable tool");
            };
            return toolResult(id, name, value, mapper);
        }
        catch (RuntimeException | JsonProcessingException ex) {
            return toolResult(id, "Kex could not complete the operation. Inspect the operator console.", true);
        }
    }

    private ResponseEntity<Object> diagnoseTopic(Object id, JsonNode arguments) {
        if (!arguments.path("topic").isTextual() || arguments.path("topic").asText().isBlank()
                || arguments.size() != 1) {
            return error(id, -32602, "kex_diagnose_topic requires only a non-blank textual topic");
        }
        KafkaViewService service = kafka.getIfAvailable();
        if (service == null) return toolResult(id, "Kafka view is disabled", true);
        try {
            return toolResult(id, "kex_diagnose_topic", service.lag(arguments.path("topic").asText()), mapper);
        }
        catch (RuntimeException | JsonProcessingException ex) {
            return toolResult(id, "Kex could not complete the operation. Inspect the operator console.", true);
        }
    }

    private ResponseEntity<Object> diagnoseProcess(Object id, JsonNode arguments) {
        if (!arguments.path("processId").isTextual() || arguments.path("processId").asText().isBlank()
                || arguments.size() != 1) {
            return error(id, -32602, "kex_diagnose_process requires only a non-blank textual processId");
        }
        SupervisionService service = supervision.getIfAvailable();
        if (service == null) return toolResult(id, "Supervision is disabled", true);
        String processId = arguments.path("processId").asText();
        var snapshot = service.snapshots().stream()
                .filter(candidate -> candidate.processId().equals(processId)).findFirst().orElse(null);
        if (snapshot == null) return toolResult(id, "Process not found", true);
        String processName = snapshot.name();
        Map<String, Object> diagnosis = Map.of(
                "processId", processId,
                "snapshot", snapshot,
                "alerts", service.alerts().stream().filter(a -> processId.equals(a.processId())).toList(),
                "incidents", service.incidents().stream()
                        .filter(incident -> incident.processNames().contains(processName)).toList(),
                "decisions", service.pending().stream().filter(d -> processId.equals(d.processId())).toList());
        try {
            return toolResult(id, "kex_diagnose_process", diagnosis, mapper);
        }
        catch (JsonProcessingException ex) {
            return toolResult(id, "Kex could not serialize the process diagnosis", true);
        }
    }

    private ResponseEntity<Object> getPrompt(Object id, JsonNode params) {
        if (!params.path("name").isTextual()) return error(id, -32602, "A textual prompt name is required");
        String name = params.path("name").asText("");
        if (!"kex_supervision_triage".equals(name)) return error(id, -32602, "Unknown prompt");
        if (params.has("arguments") && (!params.get("arguments").isObject() || !params.get("arguments").isEmpty())) {
            return error(id, -32602, "This prompt accepts no arguments");
        }
        String text = "Investigate the current Kex supervision state without changing it. "
                + "Start with kex_status and kex_overview, then inspect kex_alerts and kex_incidents. "
                + "Use kex_pending_decisions only to identify decisions awaiting human review. "
                + "Correlate the observations, distinguish facts from hypotheses, and propose diagnostic next steps. "
                + "Do not approve decisions, pause supervision, run cycles, or perform any state-changing action.";
        return result(id, Map.of(
                "description", "Read-only Kex supervision triage",
                "messages", List.of(Map.of("role", "user",
                        "content", Map.of("type", "text", "text", text)))));
    }

    private ResponseEntity<Object> jsonResource(Object id, String uri, Object value) {
        try {
            return result(id, Map.of("contents", List.of(Map.of(
                    "uri", uri, "mimeType", MediaType.APPLICATION_JSON_VALUE,
                    "text", mapper.writeValueAsString(value)))));
        }
        catch (JsonProcessingException ex) {
            return error(id, -32603, "Kex could not read the resource. Inspect the operator console.");
        }
    }

    private static List<Map<String, Object>> resourceTemplates() {
        return List.of(
                Map.of("uriTemplate", "kex://supervision/processes/{processId}",
                        "name", "Kex process snapshot",
                        "description", "Read the current supervision snapshot for one configured process.",
                        "mimeType", MediaType.APPLICATION_JSON_VALUE),
                Map.of("uriTemplate", "kex://kafka/topics/{topic}",
                        "name", "Kafka topic",
                        "description", "Read topic metadata including its partition count.",
                        "mimeType", MediaType.APPLICATION_JSON_VALUE),
                Map.of("uriTemplate", "kex://kafka/topics/{topic}/consumer-groups",
                        "name", "Kafka consumer groups",
                        "description", "Read consumer-group state and lag for one Kafka topic.",
                        "mimeType", MediaType.APPLICATION_JSON_VALUE),
                Map.of("uriTemplate", "kex://kafka/topics/{topic}/lag",
                        "name", "Kafka topic lag",
                        "description", "Read the consumer-group lag view for one Kafka topic.",
                        "mimeType", MediaType.APPLICATION_JSON_VALUE));
    }

    private List<Map<String, Object>> resources() {
        return List.of(
                McpServerCatalog.resource("kex://supervision/status", "Kex supervision status"),
                McpServerCatalog.resource("kex://supervision/overview", "Kex supervision overview"),
                McpServerCatalog.resource("kex://supervision/alerts", "Kex active alerts"),
                McpServerCatalog.resource("kex://supervision/incidents", "Kex correlated incidents"),
                McpServerCatalog.resource("kex://supervision/decisions/pending", "Kex pending decisions"),
                McpServerCatalog.resource("kex://kafka/topics", "Kafka topics"));
    }

    private static Map<String, Object> resource(String uri, String name) {
        return Map.of("uri", uri, "name", name, "mimeType", MediaType.APPLICATION_JSON_VALUE);
    }

    private ResponseEntity<Object> readResource(Object id, JsonNode params) {
        if (!params.path("uri").isTextual()) return error(id, -32602, "A textual resource URI is required");
        String uri = params.path("uri").asText("");
        if (uri.isBlank()) return error(id, -32602, "A resource URI is required");
        if (uri.equals("kex://kafka/topics")) {
            KafkaViewService service = kafka.getIfAvailable();
            if (service == null) return error(id, -32603, "Kafka view is disabled");
            return jsonResource(id, uri, service.topics());
        }
        String kafkaPrefix = "kex://kafka/topics/";
        String groupsSuffix = "/consumer-groups";
        if (uri.startsWith(kafkaPrefix) && uri.endsWith(groupsSuffix)) {
            String topic = uri.substring(kafkaPrefix.length(), uri.length() - groupsSuffix.length());
            if (topic.isBlank() || topic.contains("/")) return error(id, -32002, "Resource not found");
            KafkaViewService service = kafka.getIfAvailable();
            if (service == null) return error(id, -32603, "Kafka view is disabled");
            return jsonResource(id, uri, service.lag(topic).groups());
        }
        String kafkaSuffix = "/lag";
        if (uri.startsWith(kafkaPrefix) && uri.endsWith(kafkaSuffix)) {
            String topic = uri.substring(kafkaPrefix.length(), uri.length() - kafkaSuffix.length());
            if (topic.isBlank() || topic.contains("/")) return error(id, -32002, "Resource not found");
            KafkaViewService service = kafka.getIfAvailable();
            if (service == null) return error(id, -32603, "Kafka view is disabled");
            return jsonResource(id, uri, service.lag(topic));
        }
        if (uri.startsWith(kafkaPrefix) && !uri.endsWith(kafkaSuffix)) {
            String topic = uri.substring(kafkaPrefix.length());
            if (topic.isBlank() || topic.contains("/")) return error(id, -32002, "Resource not found");
            KafkaViewService service = kafka.getIfAvailable();
            if (service == null) return error(id, -32603, "Kafka view is disabled");
            Object metadata = service.topics().topics().stream()
                    .filter(candidate -> topic.equals(candidate.name())).findFirst().orElse(null);
            return metadata == null ? error(id, -32002, "Resource not found") : jsonResource(id, uri, metadata);
        }
        String processPrefix = "kex://supervision/processes/";
        if (uri.startsWith(processPrefix)) {
            String processId = uri.substring(processPrefix.length());
            if (processId.isBlank() || processId.contains("/")) return error(id, -32002, "Resource not found");
            SupervisionService service = supervision.getIfAvailable();
            if (service == null) return error(id, -32603, "Supervision is disabled");
            Object snapshot = service.snapshots().stream()
                    .filter(candidate -> candidate.processId().equals(processId))
                    .findFirst().orElse(null);
            if (snapshot == null) return error(id, -32002, "Resource not found");
            return jsonResource(id, uri, snapshot);
        }
        SupervisionService service = supervision.getIfAvailable();
        if (service == null) return error(id, -32603, "Supervision is disabled");
        try {
            Object value = switch (uri) {
                case "kex://supervision/status" -> service.status();
                case "kex://supervision/overview" -> service.overview();
                case "kex://supervision/alerts" -> service.alerts();
                case "kex://supervision/incidents" -> service.incidents();
                case "kex://supervision/decisions/pending" -> service.pending();
                default -> null;
            };
            if (value == null) return error(id, -32002, "Resource not found");
            return result(id, Map.of("contents", List.of(Map.of(
                    "uri", uri, "mimeType", MediaType.APPLICATION_JSON_VALUE,
                    "text", mapper.writeValueAsString(value)))));
        }
        catch (RuntimeException | JsonProcessingException ex) {
            return error(id, -32603, "Kex could not read the resource. Inspect the operator console.");
        }
    }

    private ResponseEntity<Object> authorize(HttpHeaders headers, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (authentication.getAuthorities().stream().noneMatch(authority ->
                Set.of("ROLE_OPERATOR", "ROLE_ADMIN").contains(authority.getAuthority()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<String> origins = headers.get(HttpHeaders.ORIGIN);
        if (origins != null && (origins.size() != 1 || !properties.allowedOrigins().contains(origins.get(0)))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return null;
    }

    private void publishAudit(Authentication authentication, HttpHeaders headers, String method, JsonNode params,
                              String outcome, long durationNanos) {
        McpServerAuditPublisher publisher = audit.getIfAvailable();
        if (publisher == null) return;
        String target = switch (method) {
            case "tools/call", "prompts/get" -> params.path("name").asText(null);
            case "resources/read" -> params.path("uri").asText(null);
            default -> null;
        };
        publisher.publish(new McpServerAuditEvent(Instant.now(), authentication.getName(),
                metricMethod(method), target, outcome, durationNanos));
    }

    private ResponseEntity<Object> recordTransport(String reason, ResponseEntity<Object> response) {
        meters.counter("kex.mcp.server.transport.rejected", "reason", reason).increment();
        return response;
    }

    private static String metricOutcome(ResponseEntity<Object> response) {
        if (!response.getStatusCode().is2xxSuccessful()) return "transport_rejected";
        Object body = response.getBody();
        if (body instanceof Map<?, ?> envelope && envelope.containsKey("error")) return "rpc_error";
        if (body instanceof Map<?, ?> envelope && envelope.get("result") instanceof Map<?, ?> result
                && Boolean.TRUE.equals(result.get("isError"))) return "rpc_error";
        return "success";
    }

    private static String metricMethod(String method) {
        return Set.of("initialize", "ping", "tools/list", "tools/call", "resources/list", "resources/templates/list", "resources/read",
                "prompts/list", "prompts/get").contains(method) ? method : "unknown";
    }

    private static boolean accepts(HttpHeaders headers, MediaType type) {
        return headers.getAccept().stream().anyMatch(value -> value.getQualityValue() > 0 && value.includes(type));
    }

    private static ResponseEntity<Object> toolResult(Object id, String text, boolean error) {
        return result(id, Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", error));
    }

    private static ResponseEntity<Object> toolResult(Object id, String name, Object value, ObjectMapper mapper)
            throws JsonProcessingException {
        Object structured = switch (name) {
            case "kex_alerts" -> Map.of("alerts", value);
            case "kex_incidents" -> Map.of("incidents", value);
            case "kex_pending_decisions" -> Map.of("decisions", value);
            default -> value;
        };
        return result(id, Map.of(
                "content", List.of(Map.of("type", "text", "text", mapper.writeValueAsString(value))),
                "structuredContent", structured,
                "isError", false));
    }

    private static ResponseEntity<Object> result(Object id, Object value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(envelope(id, "result", value));
    }

    private static ResponseEntity<Object> error(Object id, int code, String message) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(envelope(id, "error", Map.of("code", code, "message", message)));
    }

    private static Map<String, Object> envelope(Object id, String key, Object value) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put(key, value);
        return response;
    }
}
