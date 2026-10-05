// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.workspace;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.agent.AgentEvent;
import com.kex.agent.knowledge.KnowledgeSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceResultContractTest {
    private final Instant now = Instant.parse("2026-10-05T20:00:00Z");
    private final KnowledgeSource source = new KnowledgeSource("s1", "Mesure", "2026-10-05T19:00:00Z", "2026-10-05T21:00:00Z", "Lag 12");
    private WorkspaceResultContract.Report validate(String extra) {
        return WorkspaceResultContract.validate("{\"kind\":\"result\",\"observations\":\"Lag 12 [source:s1]\",\"uncertainties\":\"Période limitée\",\"nextAction\":\"Vérifier\"" + extra + "}", List.of(source), List.of(new AgentEvent.ToolCall("read", 2, true)), now, name -> name.equals("read"));
    }
    @Test void les_references_inventees_et_observations_sans_preuve_sont_signalees() throws Exception {
        var report = validate(",\"findings\":[{\"text\":\"Un constat\",\"sourceIds\":[\"invented\"],\"evidenceType\":\"OBSERVATION\"},{\"text\":\"Lag 12\",\"sourceIds\":[\"s1\"],\"toolNames\":[\"read\"],\"evidenceType\":\"INFERENCE\"}]");
        var json = new ObjectMapper().readTree(report.text());
        assertThat(json.path("findings").get(0).path("sourceIds").isEmpty()).isTrue();
        assertThat(json.path("findings").get(0).path("evidenceType").asText()).isEqualTo("HYPOTHESIS");
        assertThat(json.path("findings").get(1).path("evidenceType").asText()).isEqualTo("INFERENCE");
        assertThat(report.warnings()).anyMatch(w -> w.contains("Référence inconnue"));
        assertThat(report.consultations()).singleElement().satisfies(c -> { assertThat(c.status()).isEqualTo("ERROR"); assertThat(c.recoverable()).isTrue(); });
    }
    @Test void les_unites_et_series_invalides_ne_deviennent_pas_des_graphiques() throws Exception {
        var report = validate(",\"metrics\":[{\"label\":\"Lag\",\"value\":12,\"unit\":\"messages\",\"period\":\"Ce matin\",\"comparison\":{\"value\":3,\"period\":\"Hier\",\"unit\":\"secondes\"},\"points\":[{\"at\":\"hier\",\"value\":4}]},{\"label\":\"Mauvais\",\"value\":\"12\",\"unit\":\"messages\",\"period\":\"Ce matin\"}]");
        var metrics = new ObjectMapper().readTree(report.text()).path("metrics");
        assertThat(metrics.size()).isEqualTo(1); assertThat(metrics.get(0).has("comparison")).isFalse(); assertThat(metrics.get(0).has("points")).isFalse();
        assertThat(report.warnings()).hasSize(3);
    }
    @Test void le_contrat_accepte_les_tableaux_et_decisions_valides_et_rejette_les_cellules_objets() throws Exception {
        var report = validate(",\"decision\":{\"situation\":\"Lag\",\"impact\":\"Retard\",\"action\":\"Observer\",\"verify\":\"Périmètre\"},\"tables\":[{\"title\":\"Valeurs\",\"columns\":[\"Lag\"],\"rows\":[[12],[null],[true],[\"texte\"]]},{\"title\":\"Erreur\",\"columns\":[\"Lag\"],\"rows\":[[{}]]}]");
        var json = new ObjectMapper().readTree(report.text());
        assertThat(json.path("decision").path("impact").asText()).isEqualTo("Retard"); assertThat(json.path("tables").size()).isEqualTo(1);
        assertThat(report.warnings()).containsExactly("Tableau rejeté : colonnes, cellules ou limites invalides.");
    }
    @Test void les_reponses_libres_dupliquees_ou_incompletes_restent_explicitement_non_validees() {
        for (String raw : List.of("Texte libre", "null", "{}", "{\"kind\":\"result\",\"kind\":\"result\"}")) {
            var report = WorkspaceResultContract.validate(raw, List.of(), List.of(), now, name -> false);
            assertThat(report.text()).isNull(); assertThat(report.warnings()).isNotEmpty();
        }
    }
    @Test void les_dates_perimees_et_absentes_sont_signalees_sans_inventer_de_preuve() {
        var report = WorkspaceResultContract.validate("{\"kind\":\"result\",\"observations\":\"Fait [source:missing]\",\"uncertainties\":\"Limite\",\"nextAction\":\"Vérifier\"}",
                List.of(new KnowledgeSource("s", "Ancienne", null, "2026-10-01T00:00:00Z", "Texte")), List.of(new AgentEvent.ToolCall("write", 1, true)), now, name -> false);
        assertThat(report.warnings()).hasSize(3); assertThat(report.consultations().getFirst().recoverable()).isFalse();
    }
    @Test void la_clarification_validee_conserve_ses_choix() throws Exception {
        String raw = "{\"kind\":\"clarification\",\"question\":\"Quand ?\",\"choices\":[{\"label\":\"Hier\",\"value\":\"Hier\"},{\"label\":\"Ce matin\",\"value\":\"Ce matin\"}]}";
        var report = WorkspaceResultContract.validate(raw, List.of(), List.of(), now, name -> false);
        assertThat(report.warnings()).isEmpty(); assertThat(new ObjectMapper().readTree(report.text()).path("choices").size()).isEqualTo(2);
        assertThat(WorkspaceResultContract.validate(raw.replace("Quand ?", ""), List.of(), List.of(), now, name -> false).text()).isNull();
    }
    @Test void les_mesures_datees_valides_restent_exactes() throws Exception {
        var report = validate(",\"metrics\":[{\"label\":\"Lag\",\"value\":12,\"unit\":\"messages\",\"period\":\"Ce matin\",\"comparison\":{\"value\":18,\"period\":\"Hier\"},\"points\":[{\"at\":\"2026-10-05T18:00:00Z\",\"value\":18},{\"at\":\"2026-10-05T19:00:00Z\",\"value\":12}]}]");
        assertThat(report.warnings()).isEmpty(); assertThat(new ObjectMapper().readTree(report.text()).path("metrics").get(0).path("points").get(0).path("value").asInt()).isEqualTo(18);
    }
}
