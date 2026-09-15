// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;
import java.util.Map;

import com.kex.agent.mcp.McpServerUnavailableException;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolResult;
import com.kex.agent.supervision.StopReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les charges utiles reproduisent la forme réelle des outils de Kafka SQL Explorer — {@code
 * ToolResult<T>} enveloppant {@code data}, {@code coverage}, {@code warnings}, et des valeurs
 * {@code Measured} — relevée dans son dépôt plutôt que supposée.
 */
class KafkaViewServiceTest {

    private final McpToolCatalog toolCatalog = mock(McpToolCatalog.class);

    @Test
    void lit_les_topics_et_leur_couverture() {
        respondsWith(Map.of(
                "data", List.of(
                        Map.of("name", "demo.orders.1.received", "partitions", 6,
                                "records", measured(12_400), "lastActivityMs", measured(1_757_000_000_000L),
                                "deadLetter", false),
                        Map.of("name", "demo.orders.2.dlt", "partitions", 1,
                                "records", measured(17), "lastActivityMs", measured(1_757_000_000_000L),
                                "deadLetter", true)),
                "coverage", Map.of("complete", true, "stopReason", "EXHAUSTED"),
                "warnings", List.of(Map.of("severity", "WARN", "message", "schema registry absent")),
                "truncated", false));

        KafkaTopics topics = service().topics();

        assertThat(topics.unavailable()).isNull();
        assertThat(topics.topics()).extracting(KafkaTopic::name)
                .containsExactly("demo.orders.1.received", "demo.orders.2.dlt");
        assertThat(topics.topics().get(1).deadLetter()).isTrue();
        assertThat(topics.coverage().complete()).isTrue();
        assertThat(topics.warnings()).containsExactly("WARN — schema registry absent");
    }

    @Test
    void une_mesure_absente_n_est_jamais_zero() {
        // Le cœur du contrat : un lag à 0 affirme « rattrapé », une mesure absente n'affirme rien.
        // Les confondre fait lire un consumer à l'arrêt comme un consumer à jour.
        respondsWith(Map.of("data", List.of(Map.of(
                "name", "demo.orders.nested", "partitions", 6,
                "records", Map.of("measured", false, "reason", "partition 3 illisible : timeout 8 s"),
                "lastActivityMs", Map.of("measured", false, "reason", "aucun message lu"),
                "deadLetter", false))));

        KafkaTopics topics = service().topics();

        assertThat(topics.topics()).singleElement().satisfies(topic -> {
            assertThat(topic.records().measured()).isFalse();
            assertThat(topic.records().value()).isNull();
            assertThat(topic.records().reason()).isEqualTo("partition 3 illisible : timeout 8 s");
        });
    }

    @Test
    void une_mesure_mal_formee_devient_non_mesuree_et_non_zero() {
        respondsWith(Map.of("data", List.of(Map.of("name", "t", "partitions", 1, "records", "beaucoup"))));

        assertThat(service().topics().topics()).singleElement().satisfies(topic -> {
            assertThat(topic.records().measured()).isFalse();
            assertThat(topic.records().value()).isNull();
            assertThat(topic.records().reason()).isNotBlank();
            // Une mesure annoncée mesurée mais sans valeur n'est pas une mesure non plus.
            assertThat(topic.lastActivityMs().measured()).isFalse();
        });
    }

    @Test
    void reprend_le_verdict_de_l_outil_sans_le_recalculer() {
        // L'outil sait qu'un retard sans membre assigné ne se résorbera pas de lui-même ; le
        // relire depuis les nombres reviendrait à réimplémenter sa sémantique moins bien.
        respondsWith(Map.of(
                "data", Map.of("topic", "demo.orders.1.received", "groupsExamined", 2, "groupsInCluster", 3,
                        "worstVerdict", "STALLED",
                        "groups", List.of(
                                Map.of("groupId", "demo.orders.reporting", "state", "EMPTY", "type", "CLASSIC",
                                        "recordLag", measured(12_421), "lagMs", measured(480_000),
                                        "partitionsWithoutCommit", 2, "verdict", "STALLED",
                                        "explanation", "aucun membre assigné"),
                                Map.of("groupId", "demo.orders.enricher", "state", "STABLE", "type", "CONSUMER",
                                        "recordLag", measured(12), "lagMs", measured(900),
                                        "partitionsWithoutCommit", 0, "verdict", "CAUGHT_UP"))),
                "coverage", Map.of("complete", false, "stopReason", "TOPIC_LIMIT")));

        KafkaTopicLag lag = service().lag("demo.orders.1.received");

        assertThat(lag.worstVerdict()).isEqualTo("STALLED");
        assertThat(lag.groups()).extracting(KafkaGroupLag::verdict).containsExactly("STALLED", "CAUGHT_UP");
        assertThat(lag.groups().getFirst().explanation()).isEqualTo("aucun membre assigné");
        // L'écart entre examinés et existants est la part non regardée : la taire ferait lire une
        // liste courte comme une liste complète.
        assertThat(lag.groupsExamined()).isEqualTo(2);
        assertThat(lag.groupsInCluster()).isEqualTo(3);
        assertThat(lag.coverage().knownIncomplete()).isTrue();
    }

    @Test
    void un_serveur_injoignable_donne_une_vue_vide_qui_dit_pourquoi() {
        when(toolCatalog.call(anyString(), anyString(), any()))
                .thenThrow(new McpServerUnavailableException("kafka-explorer", new IllegalStateException("refusée")));

        KafkaTopics topics = service().topics();

        assertThat(topics.topics()).isEmpty();
        assertThat(topics.unavailable()).contains("kex_list_topics").contains("kafka-explorer");
    }

    @Test
    void un_outil_en_erreur_ne_passe_pas_pour_une_absence_de_topics() {
        when(toolCatalog.call(anyString(), anyString(), any()))
                .thenReturn(new McpToolResult("kafka-explorer", "kex_list_topics", true,
                        List.of("hors du périmètre autorisé"), null));

        assertThat(service().topics().unavailable()).contains("hors du périmètre autorisé");
    }

    @Test
    void sans_connexion_configuree_la_vue_se_declare_non_configuree_sans_appeler_personne() {
        KafkaViewService service = new KafkaViewService(toolCatalog,
                new KafkaProperties("", "kex_list_topics", "kex_consumer_lag", 200));

        assertThat(service.topics().unavailable()).contains("kex.agent.kafka.connection");
        assertThat(service.lag("t").unavailable()).contains("kex.agent.kafka.connection");
        verify(toolCatalog, never()).call(anyString(), anyString(), any());
    }

    @Test
    void lit_une_reponse_rendue_en_texte_json_faute_de_contenu_structure() {
        // Tous les serveurs MCP ne rendent pas de structuredContent : le bloc de texte fait foi.
        when(toolCatalog.call(anyString(), anyString(), any()))
                .thenReturn(new McpToolResult("kafka-explorer", "kex_list_topics", false,
                        List.of("un préambule qui n'est pas du JSON",
                                "{\"data\":[{\"name\":\"demo.events\",\"partitions\":3}]}"),
                        null));

        assertThat(service().topics().topics()).singleElement()
                .extracting(KafkaTopic::name).isEqualTo("demo.events");
    }

    @Test
    void une_reponse_sans_enveloppe_est_prise_telle_quelle() {
        // Un serveur MCP qui n'implémente pas le contrat Coverage reste utilisable : la couverture
        // est simplement « non rendue », et ce n'est pas présenté comme une passe complète.
        respondsWith(Map.of("topics", List.of()));

        KafkaTopics topics = service().topics();

        assertThat(topics.unavailable()).isNull();
        assertThat(topics.coverage().stopReason()).isEqualTo(StopReason.NOT_REPORTED);
        assertThat(topics.coverage().complete()).isFalse();
    }

    @Test
    void une_reponse_illisible_le_dit_au_lieu_de_rendre_une_liste_vide() {
        when(toolCatalog.call(anyString(), anyString(), any()))
                .thenReturn(new McpToolResult("kafka-explorer", "kex_list_topics", false,
                        List.of("pas du JSON du tout"), null));

        assertThat(service().topics().unavailable()).contains("illisible");
    }

    @Test
    void interroge_les_outils_configures_et_non_des_noms_codes_en_dur() {
        respondsWith(Map.of("data", List.of()));
        KafkaViewService service = new KafkaViewService(toolCatalog,
                new KafkaProperties("autre-serveur", "list_topics", "group_lag", 50));

        service.topics();
        service.lag("commandes");

        verify(toolCatalog).call(eq("autre-serveur"), eq("list_topics"), eq(Map.of("limit", 50)));
        verify(toolCatalog).call(eq("autre-serveur"), eq("group_lag"), eq(Map.of("topic", "commandes")));
    }

    /* ── Outillage ─────────────────────────────────────────────────────── */

    private KafkaViewService service() {
        return new KafkaViewService(toolCatalog,
                new KafkaProperties("kafka-explorer", "kex_list_topics", "kex_consumer_lag", 200));
    }

    private void respondsWith(Map<String, Object> payload) {
        when(toolCatalog.call(anyString(), anyString(), any()))
                .thenReturn(new McpToolResult("kafka-explorer", "outil", false, List.of(), payload));
    }

    private static Map<String, Object> measured(long value) {
        return Map.of("value", value, "measured", true);
    }
}
