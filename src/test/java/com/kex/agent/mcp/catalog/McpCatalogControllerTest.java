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
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var beans = new StaticListableBeanFactory(Map.of("supervision", supervision));
        mvc = MockMvcBuilders.standaloneSetup(new McpCatalogController(new McpRecommendedCatalog(tools),
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
