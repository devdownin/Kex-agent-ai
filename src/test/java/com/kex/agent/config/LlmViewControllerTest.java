// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@WebMvcTest(LlmViewController.class)
// Sécurité désactivée ici : elle a son propre test, ces cas visent le contrat HTTP.
@AutoConfigureMockMvc(addFilters = false)
class LlmViewControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    LlmViewService llm;

    @Test
    void la_vue_rend_le_fournisseur_effectif_et_ses_avertissements() {
        given(llm.describe()).willReturn(new LlmView("openai", "OpenRouter",
                "anthropic/claude-sonnet-4.5", "https://openrouter.ai/api/v1", true, true,
                "OPENROUTER_API_KEY", 4096, 0.2, 20, "return_error_response", "PT2M", 40, false,
                "none", false, "prompt système", List.of("Passerelle hébergée")));

        var body = assertThat(mvc.get().uri("/api/agent/llm")).hasStatusOk().bodyJson();
        body.extractingPath("$.label").isEqualTo("OpenRouter");
        body.extractingPath("$.gateway").isEqualTo(true);
        body.extractingPath("$.warnings").asArray().hasSize(1);
    }

    @Test
    void une_presence_de_cle_inconnue_se_serialise_nulle_et_non_fausse() {
        // Sérialisée à false, elle se lirait « aucune clé configurée » — une affirmation que
        // l'agent n'a pas les moyens de faire devant un fournisseur qu'il ne sait pas lire.
        given(llm.describe()).willReturn(new LlmView("ollama", "ollama", null, null, false, null,
                null, null, null, null, null, "PT2M", 40, false, "none", false, "prompt", List.of()));

        assertThat(mvc.get().uri("/api/agent/llm")).hasStatusOk().bodyJson()
                .extractingPath("$.apiKeyPresent").isNull();
    }
}
