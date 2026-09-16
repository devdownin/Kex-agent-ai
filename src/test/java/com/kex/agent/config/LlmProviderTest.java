// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import com.kex.agent.KexAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deux autoconfigurations de modèle cohabitent sur le classpath depuis l'ajout du starter OpenAI,
 * par lequel passe OpenRouter. Ces cas verrouillent laquelle s'active — et surtout que les lignes
 * de {@code application.yml} qui le décident ne sont pas décoratives : le runner ne lit pas ce
 * fichier, donc chaque cas montre ce qui arrive quand la propriété correspondante manque.
 */
class LlmProviderTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(KexAgentApplication.class)
            .withPropertyValues(
                    "spring.ai.mcp.client.enabled=false",
                    "spring.ai.anthropic.api-key=cle-anthropic",
                    "spring.ai.openai.api-key=cle-openrouter",
                    "spring.ai.openai.base-url=https://openrouter.ai/api/v1",
                    "spring.ai.model.embedding=none",
                    "kex.agent.api-key=secret");

    @Test
    void anthropic_quand_la_propriete_le_designe() {
        context.withPropertyValues("spring.ai.model.chat=anthropic").run(ctx -> {
            assertThat(ctx).hasSingleBean(ChatModel.class).hasSingleBean(ChatClient.class);
            assertThat(ctx).hasSingleBean(AnthropicChatModel.class);
            assertThat(ctx).doesNotHaveBean(OpenAiChatModel.class);
        });
    }

    @Test
    void openai_bascule_le_client_vers_openrouter() {
        context.withPropertyValues("spring.ai.model.chat=openai").run(ctx -> {
            assertThat(ctx).hasSingleBean(ChatModel.class).hasSingleBean(ChatClient.class);
            assertThat(ctx).hasSingleBean(OpenAiChatModel.class);
            assertThat(ctx).doesNotHaveBean(AnthropicChatModel.class);
        });
    }

    @Test
    void sans_la_propriete_les_deux_modeles_s_activent_et_le_contexte_echoue() {
        // La raison d'être de `spring.ai.model.chat` dans application.yml : la condition de chaque
        // autoconfiguration vaut matchIfMissing, donc les deux s'activent et plus rien ne sait quel
        // ChatModel injecter. Retirer cette ligne en la croyant redondante casse le démarrage.
        context.run(ctx -> assertThat(ctx).hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("expected single matching bean but found 2"));
    }

    /**
     * Les autres modèles que le starter OpenAI apporte. Assertions sur les beans <em>nommés</em>
     * et non sur l'interface : le scan de composants ramasse aussi les {@code @Configuration} de
     * test, et une assertion sur {@code EmbeddingModel} mesurerait le classpath de test autant que
     * le nôtre.
     */
    @Test
    void sans_les_proprietes_le_starter_openai_reveille_embeddings_images_audio_et_moderation() {
        // Un EmbeddingModel apparu sans qu'on le demande satisferait en silence la base de
        // connaissance, qui doit rester un choix explicite — même piège que le starter JDBC hors
        // du profil shared-memory. Les autres exigeraient une clé pour un service qu'on n'utilise pas.
        new ApplicationContextRunner()
                .withUserConfiguration(KexAgentApplication.class)
                .withPropertyValues("spring.ai.mcp.client.enabled=false",
                        "spring.ai.model.chat=openai",
                        "spring.ai.openai.api-key=cle-openrouter",
                        "kex.agent.api-key=secret")
                .run(ctx -> assertThat(ctx)
                        .hasBean("openAiEmbeddingModel")
                        .hasBean("openAiImageModel")
                        .hasBean("openAiSdkAudioSpeechModel")
                        .hasBean("openAiSdkAudioTranscriptionModel")
                        .hasBean("openAiSdkModerationModel"));
    }

    @Test
    void les_proprietes_d_application_les_eteignent_toutes() {
        context.withPropertyValues("spring.ai.model.chat=openai",
                        "spring.ai.model.image=none",
                        "spring.ai.model.moderation=none",
                        "spring.ai.model.audio.transcription=none",
                        "spring.ai.model.audio.speech=none")
                .run(ctx -> assertThat(ctx)
                        .doesNotHaveBean("openAiEmbeddingModel")
                        .doesNotHaveBean("openAiImageModel")
                        .doesNotHaveBean("openAiSdkAudioSpeechModel")
                        .doesNotHaveBean("openAiSdkAudioTranscriptionModel")
                        .doesNotHaveBean("openAiSdkModerationModel")
                        // Le modèle de conversation, lui, reste : c'est le seul qu'on veuille.
                        .hasBean("openAiChatModel"));
    }

    /**
     * Vérifie le chemin de configuration, pas la bibliothèque Spring AI elle-même : un préfixe de
     * propriété mal orthographié se lierait en silence sans lever d'erreur, et le cache resterait
     * simplement inactif sans que rien ne le signale.
     */
    @Test
    void active_le_cache_anthropic_sur_le_systeme_et_les_outils() {
        context.withPropertyValues("spring.ai.model.chat=anthropic",
                        "spring.ai.anthropic.chat.options.cache-options.strategy=SYSTEM_AND_TOOLS")
                .run(ctx -> {
                    AnthropicChatOptions options = (AnthropicChatOptions) ctx.getBean(ChatModel.class)
                            .getDefaultOptions();
                    assertThat(options.getCacheOptions().getStrategy())
                            .isEqualTo(AnthropicCacheStrategy.SYSTEM_AND_TOOLS);
                });
    }

    @Test
    void une_cle_absente_n_empeche_pas_le_demarrage() {
        // L'agent doit démarrer pour que /actuator/health, la console et l'introspection MCP
        // restent joignables : c'est là qu'on ira voir pourquoi rien ne répond. LlmProviderCheck
        // le signale au démarrage plutôt que de faire échouer le contexte.
        new ApplicationContextRunner()
                .withUserConfiguration(KexAgentApplication.class)
                .withPropertyValues("spring.ai.mcp.client.enabled=false",
                        "spring.ai.model.chat=openai",
                        "spring.ai.model.embedding=none",
                        "spring.ai.anthropic.api-key=",
                        "spring.ai.openai.api-key=",
                        "kex.agent.api-key=secret")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ChatClient.class));
    }
}
