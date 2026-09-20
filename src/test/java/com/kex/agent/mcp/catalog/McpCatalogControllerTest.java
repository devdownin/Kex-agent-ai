// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.util.Map;

import com.kex.agent.mcp.McpServerRegistration;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.supervision.SupervisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpCatalogControllerTest {

    private final McpToolCatalog tools = mock(McpToolCatalog.class);
    private final SupervisionService supervision = mock(SupervisionService.class);
    private final McpCatalogDiscoveryService discovery = mock(McpCatalogDiscoveryService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var beans = new StaticListableBeanFactory(Map.of("supervision", supervision));
        mvc = MockMvcBuilders.standaloneSetup(new McpCatalogController(new McpRecommendedCatalog(tools), discovery,
                beans.getBeanProvider(SupervisionService.class))).build();
    }

    @Test
    void lists_curated_readonly_entries_without_credentials() throws Exception {
        mvc.perform(get("/api/agent/mcp/catalog").principal(auth("ROLE_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("github-readonly"))
                .andExpect(jsonPath("$[0].requiresToken").value(true))
                .andExpect(jsonPath("$[1].requiresToken").value(false))
                .andExpect(jsonPath("$[0].allowedTools").isNotEmpty());
    }

    @Test
    void installs_through_existing_registration_with_fixed_destination_and_disabled_tools() throws Exception {
        mvc.perform(install("github-readonly", "{\"connection\":\"github\",\"bearerToken\":\"token-for-test\"}"))
                .andExpect(status().isCreated());
        var registration = ArgumentCaptor.forClass(McpServerRegistration.class);
        verify(tools).register(registration.capture());
        assertThat(registration.getValue().enabled()).isFalse();
        assertThat(registration.getValue().transport()).isEqualTo("HTTP");
        assertThat(registration.getValue().url()).isEqualTo("https://api.githubcopilot.com");
        assertThat(registration.getValue().endpoint()).isEqualTo("/mcp/readonly");
        assertThat(registration.getValue().bearerToken()).isEqualTo("token-for-test");
        assertThat(registration.getValue().allowedTools()).contains("get_file_contents")
                .doesNotContain("create_issue", "push_files");
        assertThat(registration.getValue().command()).isNull();
        assertThat(registration.getValue().capabilityMappings()).isEmpty();
        verify(supervision).auditAction("admin", "Installation depuis le catalogue MCP : github-readonly",
                "Connexion désactivée : github");
    }

    @Test
    void installs_public_entry_without_credentials() throws Exception {
        mvc.perform(install("microsoft-learn", "{\"connection\":\"docs\"}"))
                .andExpect(status().isCreated());
        var registration = ArgumentCaptor.forClass(McpServerRegistration.class);
        verify(tools).register(registration.capture());
        assertThat(registration.getValue().url()).isEqualTo("https://learn.microsoft.com");
        assertThat(registration.getValue().bearerToken()).isNull();
        assertThat(registration.getValue().allowedTools()).containsExactlyInAnyOrder(
                "microsoft_docs_search", "microsoft_docs_fetch", "microsoft_code_sample_search");
    }

    @Test
    void requires_admin_and_valid_entry_specific_input_before_any_network_call() throws Exception {
        mvc.perform(unauthenticatedInstall("microsoft-learn", "{\"connection\":\"docs\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(install("microsoft-learn", "{\"connection\":\"docs\"}").principal(auth("ROLE_OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(install("unknown", "{\"connection\":\"docs\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(install("github-readonly", "{\"connection\":\"github\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(install("microsoft-learn", "{\"connection\":\"docs\",\"bearerToken\":\"secret\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(install("microsoft-learn", "{\"connection\":\"../bad\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(tools, supervision);
    }

    @Test
    void does_not_claim_success_or_audit_success_when_registration_fails() throws Exception {
        when(tools.register(any())).thenThrow(new IllegalArgumentException("connection exists"));
        mvc.perform(install("microsoft-learn", "{\"connection\":\"docs\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(supervision);
    }

    @Test
    void discover_lists_sources_for_an_operator() throws Exception {
        when(discovery.discover()).thenReturn(java.util.List.of(
                McpCatalogSourceOverview.disabled("docker", "Docker MCP Catalog")));

        mvc.perform(get("/api/agent/mcp/catalog/discover").principal(auth("ROLE_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceId").value("docker"))
                .andExpect(jsonPath("$[0].enabled").value(false));
    }

    @Test
    void discover_refuses_an_unauthenticated_caller() throws Exception {
        mvc.perform(get("/api/agent/mcp/catalog/discover"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(discovery);
    }

    @Test
    void installs_a_discovered_candidate_and_audits_it() throws Exception {
        var info = new com.kex.agent.mcp.McpServerInfo("fetch", "kex-agent - fetch", "1.0.0", "2025-06-18", false,
                "CLOSED", java.util.List.of());
        when(discovery.install("docker", "fetch", new McpCatalogInstallRequest("fetch", null))).thenReturn(info);

        mvc.perform(post("/api/agent/mcp/catalog/discover/docker/fetch/install").principal(auth("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"connection\":\"fetch\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.connection").value("fetch"));

        verify(supervision).auditAction("admin",
                "Installation depuis la découverte MCP (docker/fetch)", "Connexion désactivée : fetch");
    }

    @Test
    void refuses_installing_a_discovered_candidate_without_admin() throws Exception {
        mvc.perform(post("/api/agent/mcp/catalog/discover/docker/fetch/install").principal(auth("ROLE_OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"connection\":\"fetch\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(discovery);
    }

    @Test
    void propagates_the_disqualification_status_from_the_discovery_service() throws Exception {
        when(discovery.install("docker", "shell-tool", new McpCatalogInstallRequest("shell", null)))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "Candidat disqualifié : ..."));

        mvc.perform(post("/api/agent/mcp/catalog/discover/docker/shell-tool/install").principal(auth("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"connection\":\"shell\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(supervision);
    }

    private static MockHttpServletRequestBuilder unauthenticatedInstall(String id, String json) {
        return post("/api/agent/mcp/catalog/" + id + "/install")
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static MockHttpServletRequestBuilder install(String id, String json) {
        return post("/api/agent/mcp/catalog/" + id + "/install")
                .principal(auth("ROLE_ADMIN")).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static UsernamePasswordAuthenticationToken auth(String role) {
        return UsernamePasswordAuthenticationToken.authenticated("admin", "unused", AuthorityUtils.createAuthorityList(role));
    }
}
