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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KexMcpServerInteropContractTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var beans = new StaticListableBeanFactory(Map.of(
                "supervision", org.mockito.Mockito.mock(SupervisionService.class),
                "kafka", org.mockito.Mockito.mock(KafkaViewService.class)));
        mvc = MockMvcBuilders.standaloneSetup(new KexMcpServerController(new ObjectMapper(),
                beans.getBeanProvider(SupervisionService.class), beans.getBeanProvider(KafkaViewService.class),
                new KexMcpServerProperties(true, Set.of(), 120, false),
                beans.getBeanProvider(BuildProperties.class), beans.getBeanProvider(McpServerAuditPublisher.class),
                beans.getBeanProvider(McpServerRateLimiter.class), new SimpleMeterRegistry())).build();
    }

    @Test
    void client_can_initialize_then_discover_server_capabilities() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"interop-client\",\"version\":\"1.0\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists())
                .andExpect(jsonPath("$.result.capabilities.resources").exists())
                .andExpect(jsonPath("$.result.capabilities.prompts").exists());

        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.tools.length()").value(6));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.resources").isArray());
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/list\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.prompts").isArray());
    }

    @Test
    void mutating_tools_are_not_discoverable_by_default() throws Exception {
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}"))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'kex_approve_decision')]").isEmpty())
                .andExpect(jsonPath("$.result.tools[?(@.name == 'kex_reject_decision')]").isEmpty());
    }

    private MockHttpServletRequestBuilder rpc(String body) {
        return post("/api/agent/mcp-server").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header("MCP-Protocol-Version", "2025-06-18").content(body)
                .principal(new UsernamePasswordAuthenticationToken("interop", "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_OPERATOR")));
    }
}
