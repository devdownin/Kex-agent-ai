// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.kafka.KafkaTopicLag;
import com.kex.agent.kafka.KafkaViewService;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KexMcpServerControllerTest {

    private static final String PATH = "/api/agent/mcp-server";
    private final SupervisionService supervision = mock(SupervisionService.class);
    private final KafkaViewService kafka = mock(KafkaViewService.class);
    private MockMvc mvc;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        var build = new java.util.Properties();
        build.setProperty("version", "0.6.1-test");
        var beans = new StaticListableBeanFactory(Map.of(
                "supervision", supervision, "kafka", kafka,
                "buildProperties", new BuildProperties(build)));
        meters = new SimpleMeterRegistry();
        mvc = MockMvcBuilders.standaloneSetup(new KexMcpServerController(new ObjectMapper(),
                beans.getBeanProvider(SupervisionService.class), beans.getBeanProvider(KafkaViewService.class),
                new KexMcpServerProperties(true, Set.of("https://console.example"), 120, false),
                beans.getBeanProvider(BuildProperties.class), beans.getBeanProvider(McpServerAuditPublisher.class), beans.getBeanProvider(McpServerRateLimiter.class), meters,
                new McpClientSessionRegistry())).build();
    }

    @Test
    void initializes_negotiates_and_lists_real_mcp_tools() throws Exception {
        mvc.perform(rpc("""
                {"jsonrpc":"2.0","id":"hello","method":"initialize","params":{
                  "protocolVersion":"2099-01-01","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("hello"))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
                .andExpect(jsonPath("$.result.serverInfo.version").value("0.6.1-test"))
                .andExpect(jsonPath("$.result.capabilities.tools.listChanged").value(false))
                .andExpect(jsonPath("$.result.capabilities.resources.listChanged").value(false))
                .andExpect(jsonPath("$.result.capabilities.prompts.listChanged").value(false));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                .andExpect(jsonPath("$.result.tools.length()").value(7))
                .andExpect(jsonPath("$.result.tools[0].name").value("kex_status"))
                .andExpect(jsonPath("$.result.tools[1].name").value("kex_overview"))
                .andExpect(jsonPath("$.result.tools[2].name").value("kex_alerts"))
                .andExpect(jsonPath("$.result.tools[3].name").value("kex_incidents"))
                .andExpect(jsonPath("$.result.tools[4].name").value("kex_pending_decisions"))
                .andExpect(jsonPath("$.result.tools[0].annotations.readOnlyHint").value(true))
                .andExpect(jsonPath("$.result.tools[4].annotations.destructiveHint").value(false));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}"))
                .andExpect(jsonPath("$.result").isMap());
        verifyNoInteractions(supervision);
    }

    @Test
    void lists_and_reads_read_only_supervision_resources() throws Exception {
        when(supervision.alerts()).thenReturn(List.of());
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}"))
                .andExpect(jsonPath("$.result.resources.length()").value(6))
                .andExpect(jsonPath("$.result.resources[0].uri").value("kex://supervision/status"))
                .andExpect(jsonPath("$.result.resources[2].uri").value("kex://supervision/alerts"));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"kex://supervision/alerts\"}}"))
                .andExpect(jsonPath("$.result.contents[0].mimeType").value("application/json"))
                .andExpect(jsonPath("$.result.contents[0].text").value("[]"));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"kex://unknown\"}}"))
                .andExpect(jsonPath("$.error.code").value(-32002));
    }

    @Test
    void advertises_and_reads_dynamic_process_resources() throws Exception {
        when(supervision.snapshots()).thenReturn(List.of(new ProcessSnapshot(
                "orders", "Orders", ProcessState.OK, null, null, 0L, "Nominal", Coverage.notReported())));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":16,\"method\":\"resources/templates/list\"}"))
                .andExpect(jsonPath("$.result.resourceTemplates.length()").value(4))
                .andExpect(jsonPath("$.result.resourceTemplates[0].uriTemplate")
                        .value("kex://supervision/processes/{processId}"));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"kex://supervision/processes/orders\"}}"))
                .andExpect(jsonPath("$.result.contents[0].text",
                        org.hamcrest.Matchers.containsString("\"processId\":\"orders\"")));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"kex://supervision/processes/missing\"}}"))
                .andExpect(jsonPath("$.error.code").value(-32002));
    }

    @Test
    void reads_kafka_topic_lag_as_dynamic_resource() throws Exception {
        when(kafka.lag("orders")).thenReturn(KafkaTopicLag.unavailable("orders", "broker unavailable"));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":19,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"kex://kafka/topics/orders/lag\"}}"))
                .andExpect(jsonPath("$.result.contents[0].text",
                        org.hamcrest.Matchers.containsString("\"topic\":\"orders\"")))
                .andExpect(jsonPath("$.result.contents[0].text",
                        org.hamcrest.Matchers.containsString("broker unavailable")));
    }

    @Test
    void lists_and_renders_read_only_supervision_triage_prompt() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"prompts/list\"}"))
                .andExpect(jsonPath("$.result.prompts.length()").value(1))
                .andExpect(jsonPath("$.result.prompts[0].name").value("kex_supervision_triage"));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"kex_supervision_triage\"}}"))
                .andExpect(jsonPath("$.result.messages[0].role").value("user"))
                .andExpect(jsonPath("$.result.messages[0].content.type").value("text"))
                .andExpect(jsonPath("$.result.messages[0].content.text").value(
                        org.hamcrest.Matchers.containsString("without changing it")));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"unknown\"}}"))
                .andExpect(jsonPath("$.error.code").value(-32602));
        verifyNoInteractions(supervision);
    }

    @Test
    void exposes_only_read_only_supervision_tools() throws Exception {
        when(supervision.pending()).thenReturn(List.of());
        mvc.perform(rpc(call("kex_pending_decisions", "{}")))
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").value("[]"));
        mvc.perform(rpc(call("kex_run_cycle", "{}"))).andExpect(jsonPath("$.error.code").value(-32602));
        mvc.perform(rpc(call("kex_pause", "{}"))).andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void returns_structured_content_for_successful_tools() throws Exception {
        when(supervision.alerts()).thenReturn(List.of());
        mvc.perform(rpc(call("kex_alerts", "{}")))
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").value("[]"))
                .andExpect(jsonPath("$.result.structuredContent.alerts").isArray());
    }

    @Test
    void rejects_approval_tools_unknown_arguments_and_notification_invocations() throws Exception {
        mvc.perform(rpc(call("approve", "{}"))).andExpect(jsonPath("$.error.code").value(-32602));
        mvc.perform(rpc(call("kex_status", "{\"bypassPolicy\":true}")))
                .andExpect(jsonPath("$.error.code").value(-32602));
        mvc.perform(rpc(call("kex_status", "[]"))).andExpect(jsonPath("$.error.code").value(-32602));
        mvc.perform(rpc("""
                {"jsonrpc":"2.0","method":"tools/call","params":{"name":"kex_run_cycle"}}
                """)).andExpect(status().isBadRequest());
        verifyNoInteractions(supervision);
    }

    @Test
    void rejects_ambiguous_protocol_headers_and_non_textual_identifiers() throws Exception {
        mvc.perform(rpc(call("kex_status", "{}"))
                .header("MCP-Protocol-Version", "2025-06-18", "2025-03-26"))
                .andExpect(status().isBadRequest());
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/call\","
                + "\"params\":{\"name\":7}}"))
                .andExpect(jsonPath("$.error.code").value(-32602));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":7}}"))
                .andExpect(jsonPath("$.error.code").value(-32602));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":7}}"))
                .andExpect(jsonPath("$.error.code").value(-32602));
        verifyNoInteractions(supervision);
    }

    @Test
    void protocol_errors_do_not_leak_internal_errors() throws Exception {
        mvc.perform(rpc("{"))
                .andExpect(jsonPath("$.error.code").value(-32700)).andExpect(jsonPath("$.id").isEmpty());
        mvc.perform(rpc("[]")).andExpect(jsonPath("$.error.code").value(-32600));
        mvc.perform(rpc("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(jsonPath("$.error.code").value(-32600));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"unrecognized\"}"))
                .andExpect(jsonPath("$.error.code").value(-32601));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                .andExpect(jsonPath("$.error.code").value(-32602));
        when(supervision.pending()).thenThrow(new IllegalStateException("https://secret-token@example.test"));
        mvc.perform(rpc(call("kex_pending_decisions", "{}")))
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text")
                        .value("Kex could not complete the operation. Inspect the operator console."));
    }

    @Test
    void records_bounded_metrics_for_inbound_rpc_requests() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"ping\"}"))
                .andExpect(status().isOk());
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"vendor/private-method\"}"))
                .andExpect(jsonPath("$.error.code").value(-32601));

        org.assertj.core.api.Assertions.assertThat(meters.find("kex.mcp.server.request")
                .tag("method", "ping").tag("outcome", "success").timer()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(meters.find("kex.mcp.server.request")
                .tag("method", "unknown").tag("outcome", "rpc_error").timer()).isNotNull();
    }

    @Test
    void authenticates_every_request_and_checks_roles_origins_and_transport_headers() throws Exception {
        String body = call("kex_status", "{}");
        mvc.perform(unauthenticatedRpc(body)).andExpect(status().isUnauthorized());
        mvc.perform(rpc(body).principal(auth("ROLE_CHAT"))).andExpect(status().isForbidden());
        mvc.perform(rpc(body).header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(rpc(body).header("Origin", "null")).andExpect(status().isForbidden());
        mvc.perform(rpc(body).header("MCP-Protocol-Version", "1900-01-01")).andExpect(status().isBadRequest());
        mvc.perform(rpc(body).accept(MediaType.APPLICATION_JSON)).andExpect(status().isNotAcceptable());
        org.assertj.core.api.Assertions.assertThat(meters.find("kex.mcp.server.transport.rejected")
                .tag("reason", "authorization").counter()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(meters.find("kex.mcp.server.transport.rejected")
                .tag("reason", "accept").counter()).isNotNull();
        mvc.perform(get(PATH).principal(auth("ROLE_OPERATOR"))).andExpect(status().isMethodNotAllowed());
        mvc.perform(get(PATH).principal(auth("ROLE_OPERATOR")).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(supervision);
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")
                .header("Origin", "https://console.example")).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder unauthenticatedRpc(String body) {
        return post(PATH).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).content(body);
    }

    private static MockHttpServletRequestBuilder rpc(String body) {
        return post(PATH).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .principal(auth("ROLE_OPERATOR")).content(body);
    }

    private static Authentication auth(String role) {
        return UsernamePasswordAuthenticationToken.authenticated("peer-agent", "unused", AuthorityUtils.createAuthorityList(role));
    }

    private static String call(String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + name + "\",\"arguments\":" + arguments + "}}";
    }
}
