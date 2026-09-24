// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.kafka.KafkaTopicLag;
import com.kex.agent.kafka.KafkaTopics;
import com.kex.agent.kafka.KafkaViewService;
import com.kex.agent.supervision.CorrelatedIncident;
import com.kex.agent.supervision.Coverage;
import com.kex.agent.supervision.ProcessSnapshot;
import com.kex.agent.supervision.ProcessState;
import com.kex.agent.supervision.SupervisionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KexMcpServerInteropContractTest {

    private MockMvc mvc;
    private SupervisionService supervision;
    private KafkaViewService kafka;

    @BeforeEach
    void setUp() {
        supervision = org.mockito.Mockito.mock(SupervisionService.class);
        kafka = org.mockito.Mockito.mock(KafkaViewService.class);
        when(kafka.topics()).thenReturn(KafkaTopics.unavailable("interop fixture"));
        when(kafka.lag(anyString())).thenAnswer(invocation ->
                KafkaTopicLag.unavailable(invocation.getArgument(0), "interop fixture"));
        var beans = new StaticListableBeanFactory(Map.of("supervision", supervision, "kafka", kafka));
        mvc = MockMvcBuilders.standaloneSetup(new KexMcpServerController(new ObjectMapper(),
                beans.getBeanProvider(SupervisionService.class), beans.getBeanProvider(KafkaViewService.class),
                new KexMcpServerProperties(true, Set.of(), 120, false, java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(5)),
                beans.getBeanProvider(BuildProperties.class), beans.getBeanProvider(McpServerAuditPublisher.class),
                beans.getBeanProvider(McpServerRateLimiter.class), new SimpleMeterRegistry(), new McpClientSessionRegistry(java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(5)))).build();
    }

    @Test
    void client_replays_a_complete_read_only_flow_for_current_protocol() throws Exception {
        String session = initialize("2025-06-18");

        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "2025-06-18", session)).andExpect(status().isAccepted());
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "2025-06-18", session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.tools.length()").value(7))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'kex_diagnose_topic')].outputSchema.properties.groups").exists());
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}",
                "2025-06-18", session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.resources").isArray());
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/templates/list\"}",
                "2025-06-18", session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.resourceTemplates.length()").value(4));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/list\"}",
                "2025-06-18", session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.prompts.length()").value(1));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"kex_diagnose_topic\",\"arguments\":{\"topic\":\"orders\"}}}",
                "2025-06-18", session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.topic").value("orders"));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/read\",\"params\":"
                + "{\"uri\":\"kex://kafka/topics\"}}", "2025-06-18", session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.contents[0].uri").value("kex://kafka/topics"));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"prompts/get\",\"params\":"
                + "{\"name\":\"kex_supervision_triage\",\"arguments\":{}}}", "2025-06-18", session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.messages[0].role").value("user"));
    }

    @Test
    void previous_protocol_is_supported_and_unknown_protocol_is_rejected() throws Exception {
        initialize("2025-03-26");
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}", "2099-01-01", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("Unsupported MCP protocol version"));
    }

    @Test
    void process_diagnosis_correlates_incidents_for_the_process() throws Exception {
        ProcessSnapshot snapshot = new ProcessSnapshot("orders", "Orders", ProcessState.WARNING,
                Instant.parse("2026-09-23T10:00:00Z"), 100L, 50L, "lag", Coverage.notReported());
        when(supervision.snapshots()).thenReturn(List.of(snapshot));
        when(supervision.alerts()).thenReturn(List.of());
        when(supervision.pending()).thenReturn(List.of());
        when(supervision.incidents()).thenReturn(List.of(new CorrelatedIncident(
                "cycle-1", Instant.parse("2026-09-23T10:00:00Z"), 2, List.of("Orders", "Billing"),
                ProcessState.WARNING, List.of("lag"), List.of("alert-1"))));

        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"kex_diagnose_process\",\"arguments\":{\"processId\":\"orders\"}}}",
                "2025-06-18", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.processId").value("orders"))
                .andExpect(jsonPath("$.result.structuredContent.incidents.length()").value(1));
    }

    @Test
    void mutating_tools_are_not_discoverable_by_default() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\"}",
                "2025-06-18", null))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'kex_approve_decision')]").isEmpty())
                .andExpect(jsonPath("$.result.tools[?(@.name == 'kex_reject_decision')]").isEmpty());
    }

    private String initialize(String version) throws Exception {
        MvcResult result = mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"" + version + "\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"interop-client\",\"version\":\"1.0\"}}}", version, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value(version))
                .andExpect(header().exists("Mcp-Session-Id"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists())
                .andExpect(jsonPath("$.result.capabilities.resources").exists())
                .andExpect(jsonPath("$.result.capabilities.prompts").exists())
                .andReturn();
        return result.getResponse().getHeader("Mcp-Session-Id");
    }

    private MockHttpServletRequestBuilder rpc(String body, String version, String session) {
        MockHttpServletRequestBuilder request = post("/api/agent/mcp-server").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header("MCP-Protocol-Version", version).content(body)
                .principal(new UsernamePasswordAuthenticationToken("interop", "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_OPERATOR")));
        return session == null ? request : request.header("Mcp-Session-Id", session);
    }
}
