// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contrat entre l'agent et le modèle pour un cycle : le schéma qu'on lui impose, et la lecture de
 * ce qu'il rend.
 *
 * <p>La lecture est délibérément défensive. Une sortie structurée est contrainte, pas garantie :
 * un champ manquant ou un libellé inattendu ne doit pas faire échouer tout le cycle, sinon une
 * seule anomalie mal formée effacerait les vingt-trois processus analysés correctement. Ce qui est
 * illisible devient {@link ProcessState#UNKNOWN} ou est écarté — jamais deviné.
 */
final class CycleAnalysis {

    private CycleAnalysis() {
    }

    /**
     * Le schéma décrit ce qu'on attend, les descriptions disent pourquoi : c'est le seul endroit où
     * le modèle apprend qu'une confiance doit refléter les observations et non l'aplomb du texte.
     */
    static Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "required", List.of("processes", "anomalies"),
                "additionalProperties", false,
                "properties", Map.of(
                        "processes", array(Map.of(
                                "type", "object",
                                "required", List.of("processId", "state", "coverage"),
                                "properties", ordered(
                                        "processId", field("string", "Identifiant exact fourni dans la demande"),
                                        "state", enumField(List.of("OK", "WARNING", "ERROR", "UNKNOWN"),
                                                "UNKNOWN si les outils n'ont pas permis de conclure"),
                                        "coverage", Map.of(
                                                "type", "object",
                                                "description", "Ce que les outils ont réellement lu pour ce "
                                                        + "processus. Recopie leur enveloppe coverage ; ne l'invente pas.",
                                                "required", List.of("complete"),
                                                "properties", ordered(
                                                        "complete", field("boolean",
                                                                "true seulement si stopReason vaut EXHAUSTED sur "
                                                                        + "tous les relevés. Dans le doute, false."),
                                                        "stopReason", enumField(
                                                                List.of("EXHAUSTED", "TIME_BUDGET", "TOPIC_LIMIT",
                                                                        "RECORD_LIMIT", "CANCELLED", "PARTIAL_FAILURE",
                                                                        "NOT_REPORTED"),
                                                                "Repris tel quel de l'outil ; NOT_REPORTED si aucun "
                                                                        + "outil n'a rendu d'enveloppe"),
                                                        "notReached", array(field("string",
                                                                "Nom exact de ce qui n'a pas été lu, jamais un compte")),
                                                        "detail", field("string",
                                                                "Ce qui a manqué, en une phrase"))),
                                        "lastRun", field("string", "Dernière exécution observée, format ISO-8601"),
                                        "durationMillis", field("integer", "Durée de la dernière exécution"),
                                        "delayMillis", field("integer", "Retard observé par rapport au rythme attendu"),
                                        "note", field("string", "Une phrase factuelle, sans interprétation")))),
                        "anomalies", array(Map.of(
                                "type", "object",
                                "required", List.of("processId", "title", "severity", "observations",
                                        "analysis", "confidence"),
                                "properties", ordered(
                                        "processId", field("string", "Identifiant exact du processus concerné"),
                                        "title", field("string", "Le symptôme en quelques mots"),
                                        "severity", enumField(List.of("WARNING", "ERROR"), "Gravité du symptôme"),
                                        "observations", array(Map.of(
                                                "type", "object",
                                                "required", List.of("label", "value"),
                                                "properties", ordered(
                                                        "label", field("string", "Ce qui est mesuré"),
                                                        "value", field("string", "La mesure, telle que rendue par l'outil")))),
                                        "analysis", field("string", "Ce que ces mesures impliquent, en deux phrases"),
                                        "probableCause", field("string", "Cause probable, ou omis si elle n'est pas étayée"),
                                        "confidence", field("number",
                                                "Entre 0 et 1. Fondée sur les observations ci-dessus, pas sur "
                                                        + "l'assurance de la formulation : sans mesure concluante, reste basse"),
                                        "recommendation", field("string", "L'action à mener, formulée à l'impératif"),
                                        "capability", enumField(
                                                List.of("NOTIFY", "CREATE_INCIDENT", "RESTART_CONSUMER",
                                                        "REPLAY_MESSAGES", "MODIFY_CONFIGURATION"),
                                                "Capacité correspondant à la recommandation, omise si aucune ne convient"),
                                        "knowledgeReference", field("string",
                                                "Note de connaissance interne ayant réellement informé cette "
                                                        + "analyse, citée brièvement ; omis si aucune ne s'applique"))))));
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private static Map<String, Object> field(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> enumField(List<String> values, String description) {
        return Map.of("type", "string", "enum", values, "description", description);
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static List<ProcessSnapshot> snapshots(Map<String, Object> answer, List<MonitoredProcess> known) {
        Map<String, MonitoredProcess> byId = new LinkedHashMap<>();
        known.forEach(process -> byId.put(process.id(), process));

        Map<String, ProcessSnapshot> seen = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(answer, "processes")) {
            String id = string(row, "processId");
            MonitoredProcess process = byId.get(id);
            // Un processus que le modèle a inventé n'entre pas dans le tableau de bord : on ne
            // surveille que ce qui a été déclaré.
            if (process == null) {
                continue;
            }
            Coverage coverage = coverage(row);
            seen.put(id, new ProcessSnapshot(id, process.name(),
                    concluded(state(string(row, "state")), coverage), instant(string(row, "lastRun")),
                    number(row, "durationMillis"), number(row, "delayMillis"), string(row, "note"), coverage));
        }

        // Un processus déclaré mais absent de la réponse reste affiché en UNKNOWN : le faire
        // disparaître du tableau le ferait passer pour surveillé alors qu'il ne l'est pas.
        List<ProcessSnapshot> result = new ArrayList<>(known.size());
        for (MonitoredProcess process : known) {
            result.add(seen.getOrDefault(process.id(), new ProcessSnapshot(process.id(), process.name(),
                    ProcessState.UNKNOWN, null, null, null, "Aucune donnée rendue par l'analyse",
                    Coverage.notReported())));
        }
        return List.copyOf(result);
    }

    static List<Anomaly> anomalies(Map<String, Object> answer, List<MonitoredProcess> known, String cycleId,
                                   Instant at) {
        Map<String, String> names = new LinkedHashMap<>();
        known.forEach(process -> names.put(process.id(), process.name()));

        List<Anomaly> anomalies = new ArrayList<>();
        for (Map<String, Object> row : rows(answer, "anomalies")) {
            String processId = string(row, "processId");
            String title = string(row, "title");
            if (!names.containsKey(processId) || title == null) {
                continue;
            }
            anomalies.add(new Anomaly(UUID.randomUUID().toString(), cycleId, processId, names.get(processId),
                    title, severity(string(row, "severity")), observations(row), string(row, "analysis"),
                    string(row, "probableCause"), confidence(row), string(row, "recommendation"),
                    capability(string(row, "capability")), at, string(row, "knowledgeReference")));
        }
        return List.copyOf(anomalies);
    }

    private static List<Observation> observations(Map<String, Object> row) {
        List<Observation> observations = new ArrayList<>();
        for (Map<String, Object> entry : rows(row, "observations")) {
            String label = string(entry, "label");
            if (label != null) {
                observations.add(new Observation(label, String.valueOf(entry.get("value"))));
            }
        }
        return List.copyOf(observations);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> source, String key) {
        if (source == null || !(source.get(key) instanceof List<?> list)) {
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

    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Long number(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number value ? value.longValue() : null;
    }

    /** Hors bornes, la confiance est ramenée dans [0,1] : une jauge à 3,2 n'aide personne. */
    private static double confidence(Map<String, Object> row) {
        return row.get("confidence") instanceof Number value
                ? Math.clamp(value.doubleValue(), 0.0, 1.0)
                : 0.0;
    }

    /**
     * Un relevé partiel peut prouver une présence, jamais une absence. Un {@code OK} rendu sur une
     * passe explicitement incomplète redevient donc {@code UNKNOWN} : l'anomalie était peut-être
     * précisément dans ce qui n'a pas été lu. Un {@code WARNING} ou un {@code ERROR}, eux, tiennent
     * — ce qui a été vu a bien été vu.
     *
     * <p>Une couverture simplement non remontée ne dégrade rien : la plupart des serveurs MCP ne
     * portent pas d'enveloppe, et tout basculer en {@code UNKNOWN} rendrait le tableau de bord
     * inutilisable partout ailleurs que devant Kafka SQL Explorer.
     */
    private static ProcessState concluded(ProcessState state, Coverage coverage) {
        return state == ProcessState.OK && coverage.knownIncomplete() ? ProcessState.UNKNOWN : state;
    }

    private static Coverage coverage(Map<String, Object> row) {
        return Coverage.from(row.get("coverage"));
    }

    private static ProcessState state(String value) {
        try {
            return value == null ? ProcessState.UNKNOWN : ProcessState.valueOf(value);
        }
        catch (IllegalArgumentException ex) {
            return ProcessState.UNKNOWN;
        }
    }

    /** Une anomalie sans gravité lisible est un avertissement, pas un silence. */
    private static ProcessState severity(String value) {
        ProcessState severity = state(value);
        return severity == ProcessState.UNKNOWN || severity == ProcessState.OK ? ProcessState.WARNING : severity;
    }

    private static Capability capability(String value) {
        try {
            return value == null ? null : Capability.valueOf(value);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Instant instant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }
}
