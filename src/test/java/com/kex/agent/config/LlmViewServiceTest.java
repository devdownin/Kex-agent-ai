// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.time.Duration;
import java.util.Map;

import com.kex.agent.knowledge.KnowledgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que la console affiche du modèle. Les cas tiennent sur un {@link MockEnvironment} plutôt que
 * sur un contexte : c'est bien la résolution des propriétés qui est en jeu, pas le câblage Spring.
 */
class LlmViewServiceTest {

    private static final String SECRET = "sk-or-v1-ne-doit-jamais-sortir";

    private final MockEnvironment environment = new MockEnvironment();

    private LlmView describe() {
        return describe(false);
    }

    private LlmView describe(boolean logInteractions) {
        AgentProperties agent = new AgentProperties("prompt système", 40, 4000, logInteractions,
                "secret-api", Map.of(), Duration.ofSeconds(120));
        return new LlmViewService(environment, agent, new KnowledgeProperties(false, 4, 0.6, ""))
                .describe();
    }

    @Test
    void anthropic_est_le_defaut_et_l_appel_est_direct() {
        environment.withProperty("spring.ai.anthropic.api-key", "cle")
                .withProperty("spring.ai.anthropic.chat.options.model", "claude-opus-5")
                .withProperty("spring.ai.anthropic.chat.options.max-tokens", "4096")
                .withProperty("spring.ai.anthropic.chat.options.temperature", "0.2");

        LlmView view = describe();

        assertThat(view.provider()).isEqualTo("anthropic");
        assertThat(view.label()).isEqualTo("Anthropic");
        assertThat(view.model()).isEqualTo("claude-opus-5");
        assertThat(view.maxTokens()).isEqualTo(4096);
        assertThat(view.temperature()).isEqualTo(0.2);
        assertThat(view.baseUrl()).isNull();
        assertThat(view.gateway()).isFalse();
        assertThat(view.apiKeyPresent()).isTrue();
        assertThat(view.apiKeyVariable()).isEqualTo("ANTHROPIC_API_KEY");
        assertThat(view.warnings()).isEmpty();
    }

    @Test
    void openrouter_est_nomme_et_signale_comme_intermediaire() {
        // « openai » à l'écran laisserait croire à un appel direct chez OpenAI : c'est précisément
        // la confusion qu'il ne faut pas, puisque le trajet des prompts n'est pas le même.
        openRouter();

        LlmView view = describe();

        assertThat(view.label()).isEqualTo("OpenRouter");
        assertThat(view.gateway()).isTrue();
        assertThat(view.apiKeyVariable()).isEqualTo("OPENROUTER_API_KEY");
        assertThat(view.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("quittent la machine"));
    }

    @Test
    void openai_en_direct_n_est_pas_un_intermediaire() {
        environment.withProperty("spring.ai.model.chat", "openai")
                .withProperty("spring.ai.openai.api-key", "cle")
                .withProperty("spring.ai.openai.base-url", "https://api.openai.com");

        LlmView view = describe();

        assertThat(view.label()).isEqualTo("OpenAI");
        assertThat(view.gateway()).isFalse();
        assertThat(view.warnings()).isEmpty();
    }

    @Test
    void une_base_url_absente_vaut_le_point_d_acces_du_fournisseur() {
        environment.withProperty("spring.ai.model.chat", "openai")
                .withProperty("spring.ai.openai.api-key", "cle");

        LlmView view = describe();

        assertThat(view.baseUrl()).isNull();
        assertThat(view.label()).isEqualTo("OpenAI");
        assertThat(view.gateway()).isFalse();
    }

    @Test
    void une_passerelle_inconnue_est_dite_compatible_pas_nommee_au_hasard() {
        environment.withProperty("spring.ai.model.chat", "openai")
                .withProperty("spring.ai.openai.api-key", "cle")
                .withProperty("spring.ai.openai.base-url", "https://litellm.interne/v1");

        LlmView view = describe();

        assertThat(view.label()).isEqualTo("Passerelle compatible OpenAI");
        assertThat(view.gateway()).isTrue();
    }

    @Test
    void les_identifiants_portes_par_la_base_url_ne_sont_jamais_rendus() {
        // Une base d'URL peut porter un jeton en userinfo ou en chaîne de requête. L'écran est
        // derrière le bearer de l'API, mais un secret rendu à un écran finit en capture d'écran.
        environment.withProperty("spring.ai.model.chat", "openai")
                .withProperty("spring.ai.openai.api-key", "cle")
                .withProperty("spring.ai.openai.base-url",
                        "https://utilisateur:" + SECRET + "@openrouter.ai/api/v1?key=" + SECRET);

        LlmView view = describe();

        assertThat(view.baseUrl()).isEqualTo("https://openrouter.ai/api/v1").doesNotContain(SECRET);
        assertThat(view.label()).isEqualTo("OpenRouter");
    }

    @Test
    void une_base_url_illisible_est_dite_illisible_plutot_que_recopiee() {
        environment.withProperty("spring.ai.model.chat", "openai")
                .withProperty("spring.ai.openai.api-key", "cle")
                .withProperty("spring.ai.openai.base-url", "pas une url:" + SECRET);

        assertThat(describe().baseUrl()).isEqualTo("(illisible)").doesNotContain(SECRET);
    }

    @Test
    void la_cle_n_apparait_dans_aucun_champ_de_la_vue() {
        openRouter();

        // Sur le record entier, pas champ par champ : un champ ajouté plus tard est couvert d'office.
        assertThat(describe().toString()).doesNotContain(SECRET);
    }

    @Test
    void une_cle_absente_est_un_avertissement_qui_nomme_la_variable_a_poser() {
        environment.withProperty("spring.ai.model.chat", "openai")
                .withProperty("spring.ai.openai.api-key", "");

        LlmView view = describe();

        assertThat(view.apiKeyPresent()).isFalse();
        assertThat(view.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("OPENROUTER_API_KEY"));
    }

    @Test
    void un_fournisseur_inconnu_rend_une_presence_de_cle_nulle_et_non_fausse() {
        // Un false affirmerait « pas de clé » là où la phrase vraie est « pas su regarder ». Même
        // règle que l'état illisible qui vaut UNKNOWN et jamais OK.
        environment.withProperty("spring.ai.model.chat", "ollama");

        LlmView view = describe();

        assertThat(view.apiKeyPresent()).isNull();
        assertThat(view.label()).isEqualTo("ollama");
        assertThat(view.model()).isNull();
        assertThat(view.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("n'a pas été regardé"));
    }

    @Test
    void la_journalisation_des_echanges_est_signalee_comme_un_risque() {
        openRouter();

        assertThat(describe(true).warnings()).anySatisfy(warning ->
                assertThat(warning).contains("log-interactions"));
    }

    @Test
    void les_plafonds_de_l_echange_figurent_dans_la_vue() {
        // Ce qui borne un échange se lit au même endroit que le modèle : sans cela, un échange
        // coupé à 20 appels d'outils s'explique en relisant un YAML.
        openRouter();
        environment.withProperty("spring.ai.tools.limits.max-total-tool-calls", "20")
                .withProperty("spring.ai.tools.limits.on-limit-exceeded", "return_error_response");

        LlmView view = describe();

        assertThat(view.maxToolCalls()).isEqualTo(20);
        assertThat(view.onToolLimitExceeded()).isEqualTo("return_error_response");
        assertThat(view.requestTimeout()).isEqualTo("PT2M");
        assertThat(view.maxHistoryMessages()).isEqualTo(40);
    }

    private void openRouter() {
        environment.withProperty("spring.ai.model.chat", "openai")
                .withProperty("spring.ai.openai.api-key", SECRET)
                .withProperty("spring.ai.openai.base-url", "https://openrouter.ai/api/v1")
                .withProperty("spring.ai.openai.chat.options.model", "anthropic/claude-sonnet-4.5");
    }
}
