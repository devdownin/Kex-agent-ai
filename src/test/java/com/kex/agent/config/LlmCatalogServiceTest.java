// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import com.kex.agent.knowledge.KnowledgeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le catalogue de la passerelle, contre un vrai serveur HTTP : ce qui est en jeu est la lecture
 * d'une réponse étrangère, pas le câblage Spring — un client moqué ne prouverait rien.
 */
class LlmCatalogServiceTest {

    private static final String CATALOGUE = """
            {"data":[
              {"id":"openai/gpt-5","name":"GPT-5","context_length":400000,
               "supported_parameters":["tools","temperature"]},
              {"id":"anthropic/claude-sonnet-4.5","name":"Claude Sonnet 4.5","context_length":200000,
               "supported_parameters":["tools"]},
              {"id":"meta/llama-3","name":"Llama 3","context_length":8192,
               "supported_parameters":["temperature"]},
              {"id":"interne/modele-muet"}
            ]}""";

    private final MockEnvironment environment = new MockEnvironment();

    private FakeGateway gateway;

    @BeforeEach
    void start() throws IOException {
        gateway = new FakeGateway(CATALOGUE);
        environment.withProperty("spring.ai.model.chat", "openai")
                .withProperty("spring.ai.openai.api-key", "cle-de-passerelle")
                .withProperty("spring.ai.openai.base-url", gateway.baseUrl())
                .withProperty("spring.ai.openai.chat.options.model", "anthropic/claude-sonnet-4.5");
    }

    @AfterEach
    void stop() {
        gateway.close();
    }

    private LlmCatalogService catalog() {
        return new LlmCatalogService(environment,
                new LlmViewService(environment, agent(), new KnowledgeProperties(false, 4, 0.6, "")),
                RestClient.builder());
    }

    private static AgentProperties agent() {
        return new AgentProperties("prompt", 40, false, "secret", Map.of(), Duration.ofSeconds(120));
    }

    @Test
    void le_modele_retenu_passe_en_tete_et_le_reste_suit_par_identifiant() {
        LlmModels models = catalog().models();

        assertThat(models.unavailable()).isNull();
        assertThat(models.selected()).isEqualTo("anthropic/claude-sonnet-4.5");
        assertThat(models.models()).extracting(LlmModel::id).containsExactly(
                "anthropic/claude-sonnet-4.5", "interne/modele-muet", "meta/llama-3", "openai/gpt-5");
        assertThat(models.models().getFirst().selected()).isTrue();
        assertThat(models.models().getFirst().contextLength()).isEqualTo(200000);
    }

    @Test
    void un_support_d_outils_non_publie_reste_inconnu_et_non_refuse() {
        // Ne pas le dire n'est pas le refuser. Un `false` ici écarterait de l'écran un modèle
        // parfaitement utilisable, servi par une passerelle qui n'annonce pas ses paramètres.
        LlmModels models = catalog().models();

        assertThat(models.models()).filteredOn(model -> model.id().equals("interne/modele-muet"))
                .singleElement().extracting(LlmModel::toolCalling).isNull();
        assertThat(models.models()).filteredOn(model -> model.id().equals("meta/llama-3"))
                .singleElement().extracting(LlmModel::toolCalling).isEqualTo(false);
        assertThat(models.models()).filteredOn(model -> model.id().equals("openai/gpt-5"))
                .singleElement().extracting(LlmModel::toolCalling).isEqualTo(true);
    }

    @Test
    void la_cle_part_vers_le_point_d_acces_deja_configure_et_pas_ailleurs() {
        catalog().models();

        assertThat(gateway.authorizations()).containsExactly("Bearer cle-de-passerelle");
    }

    @Test
    void une_passerelle_qui_refuse_rend_une_vue_vide_qui_dit_pourquoi() {
        // Pas d'exception : une passerelle injoignable est une information d'exploitation, et un
        // code d'erreur ferait tomber l'écran au lieu de l'informer.
        gateway.respondWith(401, "{\"error\":\"no key\"}");

        LlmModels models = catalog().models();

        assertThat(models.models()).isEmpty();
        assertThat(models.unavailable()).contains("401");
        assertThat(models.selected()).isEqualTo("anthropic/claude-sonnet-4.5");
    }

    @Test
    void une_reponse_sans_liste_data_est_dite_illisible() {
        gateway.respondWith(200, "{\"modeles\":[]}");

        assertThat(catalog().models().unavailable()).contains("illisible");
    }

    @Test
    void un_fournisseur_sans_catalogue_le_dit_plutot_que_de_rendre_une_liste_vide() {
        environment.withProperty("spring.ai.model.chat", "anthropic")
                .withProperty("spring.ai.anthropic.api-key", "cle")
                .withProperty("spring.ai.anthropic.chat.options.model", "claude-opus-5");

        LlmModels models = catalog().models();

        assertThat(models.unavailable()).contains("Anthropic");
        assertThat(models.selected()).isEqualTo("claude-opus-5");
        assertThat(gateway.authorizations()).isEmpty();
    }

    @Test
    void le_succes_est_mis_en_cache_mais_jamais_l_echec() {
        // L'appel sort de la machine et coûte à un tiers ; une passerelle qui revient, en
        // revanche, doit être vue au prochain clic et non cinq minutes plus tard.
        LlmCatalogService catalog = catalog();
        catalog.models();
        catalog.models();
        assertThat(gateway.authorizations()).hasSize(1);

        LlmCatalogService failing = catalog();
        gateway.respondWith(503, "indisponible");
        failing.models();
        failing.models();
        assertThat(gateway.authorizations()).hasSize(3);
    }
}
