// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.supervision.SupervisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var beans = new StaticListableBeanFactory(Map.of("supervision", supervision));
        mvc = MockMvcBuilders.standaloneSetup(new KexMcpServerController(new ObjectMapper(),
                beans.getBeanProvider(SupervisionService.class),
                new KexMcpServerProperties(true, Set.of("https://console.example")))).build();
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
                .andExpect(jsonPath("$.result.capabilities.tools.listChanged").value(false));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                .andExpect(jsonPath("$.result.tools.length()").value(4))
                .andExpect(jsonPath("$.result.tools[0].name").value("kex_status"))
                .andExpect(jsonPath("$.result.tools[0].annotations.readOnlyHint").value(true))
                .andExpect(jsonPath("$.result.tools[2].annotations.readOnlyHint").value(false));
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}"))
                .andExpect(jsonPath("$.result").isMap());
        verifyNoInteractions(supervision);
    }

    @Test
    void reads_and_runs_only_through_governed_service_with_real_actor() throws Exception {
        when(supervision.pending()).thenReturn(List.of());
        mvc.perform(rpc(call("kex_pending_decisions", "{}")))
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").value("[]"));
        mvc.perform(rpc(call("kex_run_cycle", "{}"))).andExpect(jsonPath("$.result.isError").value(false));
        verify(supervision).runCycle("peer-agent");
        mvc.perform(rpc(call("kex_pause", "{}"))).andExpect(jsonPath("$.result.isError").value(false));
        verify(supervision).pause("peer-agent");
    }

    @Test
    void rejects_approval_tools_unknown_arguments_and_notification_invocations() throws Exception {
        mvc.perform(rpc(call("approve", "{}"))).andExpect(jsonPath("$.error.code").value(-32602));
        mvc.perform(rpc(call("kex_run_cycle", "{\"bypassPolicy\":true}")))
                .andExpect(jsonPath("$.error.code").value(-32602));
        mvc.perform(rpc(call("kex_status", "[]"))).andExpect(jsonPath("$.error.code").value(-32602));
        mvc.perform(rpc("""
                {"jsonrpc":"2.0","method":"tools/call","params":{"name":"kex_run_cycle"}}
                """)).andExpect(status().isBadRequest());
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
    void authenticates_every_request_and_checks_roles_origins_and_transport_headers() throws Exception {
        String body = call("kex_run_cycle", "{}");
        mvc.perform(rpc(body).principal(null)).andExpect(status().isUnauthorized());
        mvc.perform(rpc(body).principal(auth("ROLE_CHAT"))).andExpect(status().isForbidden());
        mvc.perform(rpc(body).header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(rpc(body).header("Origin", "null")).andExpect(status().isForbidden());
        mvc.perform(rpc(body).header("MCP-Protocol-Version", "1900-01-01")).andExpect(status().isBadRequest());
        mvc.perform(rpc(body).accept(MediaType.APPLICATION_JSON)).andExpect(status().isNotAcceptable());
        mvc.perform(get(PATH).principal(auth("ROLE_OPERATOR"))).andExpect(status().isMethodNotAllowed());
        mvc.perform(get(PATH).principal(auth("ROLE_OPERATOR")).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(supervision);
        mvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")
                .header("Origin", "https://console.example")).andExpect(status().isOk());
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
