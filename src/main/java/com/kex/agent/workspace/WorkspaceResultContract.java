// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.workspace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kex.agent.agent.AgentEvent;
import com.kex.agent.knowledge.KnowledgeSource;

/** Contrat d'affichage : valide la structure, sans certifier les conclusions du modèle. */
public final class WorkspaceResultContract {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public record Consultation(String tool, String status, boolean recoverable, String observedAt) { }
    public record Report(String text, List<String> warnings, List<Consultation> consultations) { }
    private WorkspaceResultContract() { }

    public static Report validate(String raw, List<KnowledgeSource> sources, List<AgentEvent.ToolCall> tools,
            Instant now, Predicate<String> readOnly) {
        var warnings = new ArrayList<String>();
        var calls = tools == null ? List.<AgentEvent.ToolCall>of() : tools;
        var consultations = calls.stream().map(t -> new Consultation(t.tool(), t.failed() ? "ERROR" : "COMPLETE",
                t.failed() && readOnly.test(t.tool()), t.observedAt())).toList();
        var evidence = sources == null ? List.<KnowledgeSource>of() : sources;
        Set<String> ids = evidence.stream().map(KnowledgeSource::id).collect(Collectors.toSet());
        Set<String> names = calls.stream().map(AgentEvent.ToolCall::tool).collect(Collectors.toSet());
        try {
            if (raw == null || raw.length() > 64000) throw new IllegalArgumentException();
            JsonNode input = JSON.readTree(raw.strip().replaceAll("(?s)^```(?:json)?\\s*|\\s*```$", ""));
            if (input == null || !input.isObject()) throw new IllegalArgumentException();
            if (input.path("kind").asText().equals("clarification")) {
                var choices = input.path("choices");
                if (!text(input.get("question"), 1000) || !choices.isArray() || choices.size() < 2 || choices.size() > 4) throw new IllegalArgumentException();
                var normalizedChoices = JSON.createArrayNode();
                for (JsonNode choice : choices) {
                    if (!text(choice.get("label"), 120) || !text(choice.get("value"), 2000)) throw new IllegalArgumentException();
                    ObjectNode c = JSON.createObjectNode(); c.set("label", choice.get("label")); c.set("value", choice.get("value")); normalizedChoices.add(c);
                }
                ObjectNode clarification = JSON.createObjectNode().put("kind", "clarification"); clarification.set("question", input.get("question")); clarification.set("choices", normalizedChoices);
                return new Report(JSON.writeValueAsString(clarification), List.of(), consultations);
            }
            if (!input.path("kind").asText().equals("result")) throw new IllegalArgumentException();
            ObjectNode out = JSON.createObjectNode().put("kind", "result");
            for (String key : List.of("observations", "uncertainties", "nextAction")) {
                if (!text(input.get(key), 16000)) throw new IllegalArgumentException();
                out.set(key, input.get(key));
            }
            if (input.has("conclusion")) copyText(input, out, "conclusion", 500, warnings);
            if (input.has("decision")) {
                JsonNode decision = input.get("decision");
                if (decision.isObject() && List.of("situation", "impact", "action", "verify").stream().allMatch(k -> text(decision.get(k), 2000))) {
                    ObjectNode normalized = JSON.createObjectNode();
                    List.of("situation", "impact", "action", "verify").forEach(k -> normalized.set(k, decision.get(k)));
                    out.set("decision", normalized);
                } else warnings.add("Synthèse décisionnelle rejetée : quatre rubriques textuelles sont nécessaires.");
            }
            if (input.has("findings")) {
                var accepted = JSON.createArrayNode();
                JsonNode findings = input.get("findings");
                if (!findings.isArray() || findings.size() > 20) warnings.add("Constats rejetés : liste limitée à 20 éléments.");
                else for (JsonNode finding : findings) {
                    if (!text(finding.get("text"), 4000)) { warnings.add("Constat mal formé rejeté."); continue; }
                    ObjectNode f = JSON.createObjectNode().put("text", finding.get("text").asText());
                    f.set("sourceIds", references(finding.get("sourceIds"), ids, warnings));
                    f.set("toolNames", references(finding.get("toolNames"), names, warnings));
                    String type = finding.path("evidenceType").asText("HYPOTHESIS");
                    if (!Set.of("OBSERVATION", "INFERENCE", "HYPOTHESIS").contains(type)) { type = "HYPOTHESIS"; warnings.add("Qualification inconnue remplacée par hypothèse."); }
                    if (type.equals("OBSERVATION") && f.path("sourceIds").isEmpty() && f.path("toolNames").isEmpty()) {
                        type = "HYPOTHESIS"; warnings.add("Observation sans preuve identifiable présentée comme hypothèse.");
                    }
                    f.put("evidenceType", type);
                    f.set("contradictionIds", references(finding.get("contradictionIds"), ids, warnings));
                    accepted.add(f);
                }
                out.set("findings", accepted);
            }
            if (input.has("tables")) {
                var accepted = JSON.createArrayNode(); var tables = input.get("tables");
                if (!tables.isArray() || tables.size() > 3) warnings.add("Tableaux rejetés : maximum 3 tableaux.");
                else for (JsonNode table : tables) {
                    var columns = table.path("columns"); var rows = table.path("rows");
                    boolean valid = text(table.get("title"), 200) && columns.isArray() && columns.size() > 0 && columns.size() <= 12 && rows.isArray() && rows.size() <= 200;
                    if (valid) for (JsonNode column : columns) valid &= text(column, 120);
                    if (valid) for (JsonNode row : rows) {
                        if (!row.isArray() || row.size() != columns.size()) { valid = false; break; }
                        for (JsonNode cell : row) valid &= cell.isNull() || cell.isBoolean() || number(cell) || cell.isTextual() && cell.asText().length() <= 2000;
                    }
                    if (valid) { ObjectNode t = JSON.createObjectNode(); List.of("title", "columns", "rows").forEach(k -> t.set(k, table.get(k))); accepted.add(t); }
                    else warnings.add("Tableau rejeté : colonnes, cellules ou limites invalides.");
                }
                out.set("tables", accepted);
            }
            if (input.has("metrics")) {
                var accepted = JSON.createArrayNode(); var metrics = input.get("metrics");
                if (!metrics.isArray() || metrics.size() > 12) warnings.add("Métriques rejetées : maximum 12 métriques.");
                else for (JsonNode metric : metrics) {
                    if (!text(metric.get("label"), 200) || !number(metric.get("value")) || !text(metric.get("unit"), 80) || !text(metric.get("period"), 200)) { warnings.add("Métrique rejetée : valeur, unité ou période invalide."); continue; }
                    ObjectNode m = JSON.createObjectNode(); List.of("label", "value", "unit", "period").forEach(k -> m.set(k, metric.get(k)));
                    if (metric.has("comparison")) {
                        var comparison = metric.get("comparison");
                        if (number(comparison.get("value")) && text(comparison.get("period"), 200) && (!comparison.has("unit") || comparison.path("unit").asText().equals(metric.path("unit").asText()))) {
                            ObjectNode c = JSON.createObjectNode(); c.set("value", comparison.get("value")); c.set("period", comparison.get("period")); m.set("comparison", c);
                        } else warnings.add("Comparaison rejetée : valeur, période ou unité incompatible.");
                    }
                    if (metric.has("points")) {
                        var points = metric.get("points"); var dates = new java.util.HashSet<Instant>();
                        boolean valid = points.isArray() && points.size() <= 200;
                        if (valid) for (JsonNode point : points) valid &= number(point.get("value")) && date(point.path("at").asText()) && dates.add(Instant.parse(point.path("at").asText()));
                        if (valid) { var normalized = JSON.createArrayNode(); for (JsonNode point : points) { ObjectNode p = JSON.createObjectNode(); p.set("at", point.get("at")); p.set("value", point.get("value")); normalized.add(p); } m.set("points", normalized); }
                        else warnings.add("Série rejetée : dates ISO distinctes et valeurs numériques requises.");
                    }
                    accepted.add(m);
                }
                out.set("metrics", accepted);
            }
            for (String key : List.of("observations", "uncertainties", "nextAction", "conclusion")) {
                var matcher = java.util.regex.Pattern.compile("\\[source:([^\\]\\r\\n]+)\\]").matcher(out.path(key).asText());
                while (matcher.find()) if (!ids.contains(matcher.group(1))) warnings.add("Une citation ne correspond à aucune source fournie.");
            }
            Set<String> supported = Set.of("kind", "observations", "uncertainties", "nextAction", "conclusion", "decision", "findings", "tables", "metrics");
            input.fieldNames().forEachRemaining(key -> { if (!supported.contains(key)) warnings.add("Champs complémentaires non validés : consultez les données brutes."); });
            for (KnowledgeSource source : evidence) {
                if (!date(source.observedAt())) warnings.add("Une source ne dispose pas d’un horodatage ISO exploitable.");
                if (date(source.validUntil()) && !Instant.parse(source.validUntil()).isAfter(now)) warnings.add("Une source a une validité expirée.");
            }
            return new Report(JSON.writeValueAsString(out), warnings.stream().distinct().limit(30).toList(), consultations);
        } catch (Exception ex) {
            warnings.add("Réponse non conforme au contrat : affichage du texte reçu sans validation structurelle.");
            return new Report(null, warnings.stream().distinct().limit(30).toList(), consultations);
        }
    }
    private static JsonNode references(JsonNode values, Set<String> known, List<String> warnings) {
        var result = JSON.createArrayNode();
        if (values == null) return result;
        if (!values.isArray() || values.size() > 10) { warnings.add("Liste de preuves mal formée rejetée."); return result; }
        var seen = new java.util.HashSet<String>();
        for (JsonNode value : values) {
            if (text(value, 200) && known.contains(value.asText())) { if (seen.add(value.asText())) result.add(value.asText()); }
            else warnings.add("Référence inconnue rejetée : seules les preuves réellement fournies sont associées.");
        }
        return result;
    }
    private static void copyText(JsonNode input, ObjectNode out, String key, int max, List<String> warnings) {
        if (text(input.get(key), max)) out.set(key, input.get(key)); else warnings.add("Champ " + key + " rejeté : texte invalide ou trop long.");
    }
    private static boolean text(JsonNode node, int max) { return node != null && node.isTextual() && !node.asText().isBlank() && node.asText().length() <= max; }
    private static boolean number(JsonNode node) { return node != null && node.isNumber() && Double.isFinite(node.doubleValue()); }
    private static boolean date(String value) { try { Instant.parse(value); return true; } catch (Exception ex) { return false; } }
}
