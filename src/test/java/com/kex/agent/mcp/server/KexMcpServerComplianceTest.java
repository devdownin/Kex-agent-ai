// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.kafka.KafkaViewService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KexMcpServerComplianceTest {

    private static final String PATH = "/api/agent/mcp-server";
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var supervision = mock(SupervisionService.class);
        var kafka = mock(KafkaViewService.class);
        var beans = new StaticListableBeanFactory(Map.of("supervision", supervision, "kafka", kafka));
        mvc = MockMvcBuilders.standaloneSetup(new KexMcpServerController(new ObjectMapper(),
                beans.getBeanProvider(SupervisionService.class), beans.getBeanProvider(KafkaViewService.class),
                new KexMcpServerProperties(true, Set.of("https://console.example")),
                beans.getBeanProvider(BuildProperties.class), beans.getBeanProvider(McpServerAuditPublisher.class),
                new SimpleMeterRegistry())).build();
    }

    @Test
    void advertises_typed_read_only_tools_only() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(5))
                .andExpect(jsonPath("$.result.tools[0].outputSchema.type").value("object"))
                .andExpect(jsonPath("$.result.tools[0].outputSchema.properties.state.type").value("string"))
                .andExpect(jsonPath("$.result.tools[2].outputSchema.type").value("object"))
                .andExpect(jsonPath("$.result.tools[2].outputSchema.properties.alerts.type").value("array"))
                .andExpect(jsonPath("$.result.tools[0].annotations.readOnlyHint").value(true))
                .andExpect(jsonPath("$.result.tools[0].annotations.destructiveHint").value(false));
    }

    @Test
    void implements_core_json_rpc_error_contract() throws Exception {
        mvc.perform(rpc("{")).andExpect(jsonPath("$.error.code").value(-32700));
        mvc.perform(rpc("[]")).andExpect(jsonPath("$.error.code").value(-32600));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"missing\"}"))
                .andExpect(jsonPath("$.error.code").value(-32601));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"kex_pause\"}}"))
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void exposes_resources_templates_and_prompts_without_mutation_capabilities() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/templates/list\"}"))
                .andExpect(jsonPath("$.result.resourceTemplates.length()").value(2));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/list\"}"))
                .andExpect(jsonPath("$.result.prompts[0].name").value("kex_supervision_triage"));
    }


    @Test
    void rejects_invalid_transport_and_protocol_inputs() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .content("{}").principal(new UsernamePasswordAuthenticationToken("operator", "n/a",
                                AuthorityUtils.createAuthorityList("ROLE_OPERATOR"))))
                .andExpect(status().isNotAcceptable());
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"ping\"}")
                        .header("MCP-Protocol-Version", "2099-01-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\","
                        + "\"params\":{\"cursor\":\"next\"}}"))
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void notifications_cannot_invoke_request_methods() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"kex_status\"}}"))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder rpc(String body) {
        return post(PATH).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header("MCP-Protocol-Version", "2025-06-18").content(body)
                .principal(new UsernamePasswordAuthenticationToken("operator", "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_OPERATOR")));
    }
}
