// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in contract against a running KafkaExplorer /mcp and a seeded demo topic.
 * Run with KAFKA_EXPLORER_MCP_URL=http://localhost:8080 and
 * ./mvnw -Dtest=KafkaExplorerLiveContractTest test. A missing URL skips the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mcp-it")
class KafkaExplorerLiveContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TOOLS = Set.of("kex_process_health", "kex_topic_activity",
            "kex_diagnose_consumer", "kex_flow_health", "kex_compare_process_state",
            "kex_incident_evidence");

    @Autowired McpToolCatalog catalog;

    @Test
    void kex_consumes_the_six_real_kafka_explorer_tools() throws Exception {
        String url = System.getenv("KAFKA_EXPLORER_MCP_URL");
        assumeTrue(url != null && !url.isBlank(), "Set KAFKA_EXPLORER_MCP_URL for the live contract");
        String topic = System.getenv().getOrDefault("KAFKA_EXPLORER_TEST_TOPIC", "demo.orders.1.received");
        String group = System.getenv().getOrDefault("KAFKA_EXPLORER_TEST_GROUP", "demo-orders");
        String token = System.getenv("KAFKA_EXPLORER_MCP_TOKEN");
        McpServerRegistration registration = new McpServerRegistration("explorer-contract", "HTTP", url,
                "/mcp", token, Map.of(), null, List.of(), Map.of(), true, TOOLS, Map.of());
        try {
            var server = catalog.register(registration);
            assertThat(server.tools()).extracting(McpToolInfo::name).containsAll(TOOLS);
            var stages = List.of(Map.of("name", "input", "topic", topic));
            Map<String, Object> process = Map.of("process", "contract-demo", "stages", stages);
            JsonNode health = call("kex_process_health", process);
            String measurementId = health.path("data").path("measurementId").asText();
            assertThat(measurementId).isNotBlank();
            assertThat(health.path("coverage").isObject()).isTrue();
            assertThat(health.path("data").path("offsetsProduced").path("measured").isBoolean()).isTrue();
            for (var request : Map.<String, Map<String, Object>>of(
                    "kex_topic_activity", Map.of("topics", List.of(topic)),
                    "kex_diagnose_consumer", Map.of("topic", topic, "groupId", group),
                    "kex_flow_health", Map.of("topics", List.of(topic)),
                    "kex_incident_evidence", process).entrySet()) {
                JsonNode result = call(request.getKey(), request.getValue());
                assertThat(result.path("coverage").isObject()).as(request.getKey()).isTrue();
            }
            JsonNode comparison = call("kex_compare_process_state", Map.of(
                    "process", "contract-demo", "stages", stages, "beforeMeasurementId", measurementId));
            assertThat(comparison.path("data").path("beforeMeasurementId").asText())
                    .isEqualTo(measurementId);
            assertThat(comparison.path("data").path("afterMeasurementId").asText()).isNotBlank();
            assertThat(comparison.path("data").path("verdict").asText()).isNotBlank();
        } finally {
            if (catalog.dynamicConnectionNames().contains("explorer-contract")) {
                catalog.unregister("explorer-contract");
            }
        }
    }

    private JsonNode call(String tool, Map<String, Object> arguments) throws Exception {
        McpToolResult result = catalog.call("explorer-contract", tool, arguments);
        assertThat(result.error()).as(tool).isFalse();
        if (result.structuredContent() != null) {
            return JSON.valueToTree(result.structuredContent());
        }
        assertThat(result.content()).as(tool).isNotEmpty();
        return JSON.readTree(result.content().get(0));
    }
}
