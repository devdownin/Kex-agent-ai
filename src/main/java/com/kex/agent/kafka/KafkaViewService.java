// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolResult;
import com.kex.agent.supervision.Coverage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * La vue technique : ce que l'agent peut réellement observer du cluster, au second niveau, pour que
 * le tableau de bord métier n'en soit pas saturé.
 *
 * <p>Elle n'interroge rien elle-même : elle appelle les outils du serveur MCP et traduit leur
 * réponse. Toute la sémantique Kafka reste chez Kafka SQL Explorer — ici on lit, on ne recalcule
 * pas. En particulier les verdicts de lag sont repris tels quels : c'est l'outil qui sait qu'un
 * retard sans membre assigné ne se résorbera pas de lui-même.
 *
 * <p>Rien n'y lève d'exception vers l'appelant. Un outil absent, un serveur injoignable ou une
 * réponse illisible produisent une vue vide <em>qui dit pourquoi</em> : une liste vide sans motif
 * se lirait « aucun topic », ce qui est une affirmation qu'on n'a pas les moyens de faire.
 */
@Service
public class KafkaViewService {

    private static final Logger log = LoggerFactory.getLogger(KafkaViewService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpToolCatalog toolCatalog;
    private final KafkaProperties properties;

    KafkaViewService(McpToolCatalog toolCatalog, KafkaProperties properties) {
        this.toolCatalog = toolCatalog;
        this.properties = properties;
    }

    public KafkaTopics topics() {
        if (!StringUtils.hasText(properties.connection())) {
            return KafkaTopics.unavailable("Aucune connexion MCP configurée (kex.agent.kafka.connection)");
        }
        Envelope envelope = call(properties.topicsTool(), Map.of("limit", properties.maxTopics()));
        if (envelope.failure() != null) {
            return KafkaTopics.unavailable(envelope.failure());
        }
        List<KafkaTopic> topics = new ArrayList<>();
        for (Map<String, Object> row : rows(envelope.data())) {
            String name = text(row.get("name"));
            if (name != null) {
                topics.add(new KafkaTopic(name, integer(row.get("partitions")),
                        MeasuredValue.from(row.get("records")), MeasuredValue.from(row.get("lastActivityMs")),
                        row.get("deadLetter") instanceof Boolean flag && flag));
            }
        }
        return new KafkaTopics(List.copyOf(topics), envelope.coverage(), envelope.warnings(),
                envelope.truncated(), null);
    }

    public KafkaTopicLag lag(String topic) {
        if (!StringUtils.hasText(properties.connection())) {
            return KafkaTopicLag.unavailable(topic,
                    "Aucune connexion MCP configurée (kex.agent.kafka.connection)");
        }
        Envelope envelope = call(properties.lagTool(), Map.of("topic", topic));
        if (envelope.failure() != null) {
            return KafkaTopicLag.unavailable(topic, envelope.failure());
        }
        if (!(envelope.data() instanceof Map<?, ?> raw)) {
            return KafkaTopicLag.unavailable(topic, "Réponse de " + properties.lagTool() + " illisible");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) raw;

        List<KafkaGroupLag> groups = new ArrayList<>();
        for (Map<String, Object> row : rows(data.get("groups"))) {
            groups.add(new KafkaGroupLag(text(row.get("groupId")), text(row.get("state")), text(row.get("type")),
                    MeasuredValue.from(row.get("recordLag")), MeasuredValue.from(row.get("lagMs")),
                    integer(row.get("partitionsWithoutCommit")), text(row.get("verdict")),
                    text(row.get("explanation")), text(row.get("error"))));
        }
        return new KafkaTopicLag(topic, List.copyOf(groups), integer(data.get("groupsExamined")),
                integer(data.get("groupsInCluster")), text(data.get("worstVerdict")), envelope.coverage(),
                envelope.warnings(), envelope.truncated(), null);
    }

    /* ── Appel et lecture de l'enveloppe ───────────────────────────────── */

    /** @param failure ce qui a empêché d'aboutir, {@code null} quand l'appel a rendu une charge utile */
    private record Envelope(Object data, Coverage coverage, List<String> warnings, boolean truncated,
                            String failure) {

        static Envelope failed(String reason) {
            return new Envelope(null, Coverage.notReported(), List.of(), false, reason);
        }
    }

    private Envelope call(String tool, Map<String, Object> arguments) {
        if (!StringUtils.hasText(tool)) {
            return Envelope.failed("Aucun outil configuré pour cette vue");
        }
        McpToolResult result;
        try {
            result = toolCatalog.call(properties.connection(), tool, arguments);
        }
        catch (RuntimeException ex) {
            // Un serveur injoignable ou un outil absent est un fait à afficher, pas une panne de
            // l'agent : la vue le nomme et le reste de la console continue de fonctionner.
            log.warn("Vue Kafka : appel de {} sur {} en échec", tool, properties.connection(), ex);
            return Envelope.failed("%s sur « %s » : %s".formatted(tool, properties.connection(),
                    ex.getMessage()));
        }
        if (result.error()) {
            return Envelope.failed("%s a rendu une erreur : %s".formatted(tool,
                    String.join(" ", result.content())));
        }
        Map<String, Object> payload = payload(result);
        if (payload == null) {
            return Envelope.failed("Réponse de " + tool + " illisible");
        }
        // Un outil sans enveloppe rend directement sa charge utile : on la prend telle quelle
        // plutôt que d'exiger une forme que seul Kafka SQL Explorer produit.
        Object data = payload.containsKey("data") ? payload.get("data") : payload;
        return new Envelope(data, Coverage.from(payload.get("coverage")), warnings(payload.get("warnings")),
                payload.get("truncated") instanceof Boolean flag && flag, null);
    }

    /** Le contenu structuré quand le serveur en rend un, sinon le premier bloc de texte en JSON. */
    private static Map<String, Object> payload(McpToolResult result) {
        if (result.structuredContent() instanceof Map<?, ?> structured) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) structured;
            return map;
        }
        for (String text : result.content()) {
            try {
                if (JSON.readTree(text).isObject()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = JSON.readValue(text, Map.class);
                    return map;
                }
            }
            catch (JsonProcessingException ex) {
                // Un bloc non JSON n'est pas une erreur : un serveur peut mêler texte et données.
            }
        }
        return null;
    }

    private static List<String> warnings(Object candidate) {
        List<String> messages = new ArrayList<>();
        for (Map<String, Object> row : rows(candidate)) {
            String message = text(row.get("message"));
            if (message != null) {
                String severity = text(row.get("severity"));
                messages.add(severity == null ? message : severity + " — " + message);
            }
        }
        return List.copyOf(messages);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object candidate) {
        if (!(candidate instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add((Map<String, Object>) map);
            }
        }
        return rows;
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
