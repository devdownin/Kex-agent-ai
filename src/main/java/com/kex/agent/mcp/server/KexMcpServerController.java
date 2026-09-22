// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.supervision.SupervisionService;
import com.kex.agent.kafka.KafkaViewService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    private static final Set<String> PROTOCOLS = Set.of(PROTOCOL, "2025-03-26");
    private static final int MAX_REQUEST_LENGTH = 65_536;
    private static final Map<String, Object> EMPTY_SCHEMA = Map.of(
            "type", "object", "properties", Map.of(), "additionalProperties", false);
    // Phase 1 deliberately exposes observation only. Mutating supervision operations stay behind
    // Kex's operator API/console until MCP-specific approval and audit semantics are defined.
    private static final List<Map<String, Object>> PROMPTS = List.of(
            Map.of("name", "kex_supervision_triage", "title", "Kex supervision triage",
                    "description", "Guide a read-only investigation using Kex supervision tools and resources."));
    private static final List<Map<String, Object>> TOOLS = List.of(
            tool("kex_status", "Read Kex supervision status."),
            tool("kex_overview", "Read the current supervision overview, including process states and counts."),
            tool("kex_alerts", "Read the currently active supervision alerts."),
            tool("kex_incidents", "Read incidents correlated from the latest supervision cycle."),
            tool("kex_pending_decisions", "Read decisions awaiting human review. Cannot approve them."));

    private final ObjectMapper mapper;
    private final ObjectProvider<SupervisionService> supervision;
    private final ObjectProvider<KafkaViewService> kafka;
    private final KexMcpServerProperties properties;
    private final MeterRegistry meters;

    public KexMcpServerController(ObjectMapper mapper, ObjectProvider<SupervisionService> supervision,
                                  ObjectProvider<KafkaViewService> kafka,
                                  KexMcpServerProperties properties, MeterRegistry meters) {
        this.mapper = mapper;
        this.supervision = supervision;
        this.kafka = kafka;
        this.properties = properties;
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
        if (rejected != null) return rejected;
        if (!accepts(headers, MediaType.APPLICATION_JSON) || !accepts(headers, MediaType.TEXT_EVENT_STREAM)) {
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
        }
        if (body.length() > MAX_REQUEST_LENGTH) return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();

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
        sample.stop(meters.timer("kex.mcp.server.request", "method", metricMethod(method),
                "outcome", response.getStatusCode().is2xxSuccessful() ? "success" : "rejected"));
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
                "serverInfo", Map.of("name", "kex-agent-ai", "version", "1.0.0"),
                "capabilities", Map.of("tools", Map.of("listChanged", false),
                        "resources", Map.of("listChanged", false), "prompts", Map.of("listChanged", false)),
                "instructions", "Read-only Kex supervision endpoint. Human approvals and all state changes "
                        + "stay in Kex's authenticated operator API and console."));
    }

    private ResponseEntity<Object> call(Object id, JsonNode params) {
        String name = params.path("name").asText("");
        if (TOOLS.stream().noneMatch(tool -> tool.get("name").equals(name))) {
            return error(id, -32602, "Unknown tool");
        }
        if (!params.path("name").isTextual()) return error(id, -32602, "A textual tool name is required");
        if (params.has("arguments") && (!params.get("arguments").isObject() || !params.get("arguments").isEmpty())) {
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
            return toolResult(id, value, mapper);
        }
        catch (RuntimeException | JsonProcessingException ex) {
            // Provider errors can contain URLs or credentials. They belong in existing audited
            // services, not in a response passed to another model.
            return toolResult(id, "Kex could not complete the operation. Inspect the operator console.", true);
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
                Map.of("uriTemplate", "kex://kafka/topics/{topic}/lag",
                        "name", "Kafka topic lag",
                        "description", "Read the consumer-group lag view for one Kafka topic.",
                        "mimeType", MediaType.APPLICATION_JSON_VALUE));
    }

    private List<Map<String, Object>> resources() {
        return List.of(
                resource("kex://supervision/status", "Kex supervision status"),
                resource("kex://supervision/overview", "Kex supervision overview"),
                resource("kex://supervision/alerts", "Kex active alerts"),
                resource("kex://supervision/incidents", "Kex correlated incidents"),
                resource("kex://supervision/decisions/pending", "Kex pending decisions"));
    }

    private static Map<String, Object> resource(String uri, String name) {
        return Map.of("uri", uri, "name", name, "mimeType", MediaType.APPLICATION_JSON_VALUE);
    }

    private ResponseEntity<Object> readResource(Object id, JsonNode params) {
        if (!params.path("uri").isTextual()) return error(id, -32602, "A textual resource URI is required");
        String uri = params.path("uri").asText("");
        if (uri.isBlank()) return error(id, -32602, "A resource URI is required");
        String kafkaPrefix = "kex://kafka/topics/";
        String kafkaSuffix = "/lag";
        if (uri.startsWith(kafkaPrefix) && uri.endsWith(kafkaSuffix)) {
            String topic = uri.substring(kafkaPrefix.length(), uri.length() - kafkaSuffix.length());
            if (topic.isBlank() || topic.contains("/")) return error(id, -32002, "Resource not found");
            KafkaViewService service = kafka.getIfAvailable();
            if (service == null) return error(id, -32603, "Kafka view is disabled");
            return jsonResource(id, uri, service.lag(topic));
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

    private static String metricMethod(String method) {
        return Set.of("initialize", "ping", "tools/list", "tools/call", "resources/list", "resources/templates/list", "resources/read",
                "prompts/list", "prompts/get").contains(method) ? method : "unknown";
    }

    private static boolean accepts(HttpHeaders headers, MediaType type) {
        return headers.getAccept().stream().anyMatch(value -> value.getQualityValue() > 0 && value.includes(type));
    }

    private static Map<String, Object> tool(String name, String description) {
        return Map.of("name", name, "description", description, "inputSchema", EMPTY_SCHEMA,
                "annotations", Map.of("readOnlyHint", true, "destructiveHint", false,
                        "idempotentHint", true, "openWorldHint", false));
    }

    private static ResponseEntity<Object> toolResult(Object id, String text, boolean error) {
        return result(id, Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", error));
    }

    private static ResponseEntity<Object> toolResult(Object id, Object value, ObjectMapper mapper)
            throws JsonProcessingException {
        return result(id, Map.of(
                "content", List.of(Map.of("type", "text", "text", mapper.writeValueAsString(value))),
                "structuredContent", value,
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
