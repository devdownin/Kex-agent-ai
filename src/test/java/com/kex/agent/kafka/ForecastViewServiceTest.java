// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;
import java.util.Map;

import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForecastViewServiceTest {
    private final McpToolCatalog catalog = mock(McpToolCatalog.class);
    private final ForecastViewService service = new ForecastViewService(catalog,
            new KafkaProperties("kafka-explorer", "topics", "lag", 200));
    private static final String ID = "approved-series";

    @Test
    void lit_le_contrat_reel_sans_declencher_d_inference() {
        respond("kex_list_forecastable_metrics", List.of(metric()));
        respond("kex_forecast_metric", measured(record(history(), forecast())));
        respond("kex_metric_history", measured(history()));
        respond("kex_get_forecast_quality", Map.of("measured", false, "reason", "Pas encore évaluée"));
        var detail = service.detail(ID);
        assertThat(detail.forecast().unavailable()).isNull();
        assertThat(detail.forecast().data().path("value").path("forecast").path("points").get(0)
                .path("central").asDouble()).isEqualTo(0);
        assertThat(detail.quality().data().path("measured").asBoolean()).isFalse();
        for (String tool : List.of("kex_forecast_metric", "kex_metric_history", "kex_get_forecast_quality")) {
            verify(catalog).call("kafka-explorer", tool, Map.of("seriesId", ID));
        }
    }

    @Test
    void refuse_une_serie_inconnue_avant_toute_lecture() {
        respond("kex_list_forecastable_metrics", List.of(metric()));
        assertThat(service.detail("other").forecast().unavailable()).contains("absente");
        verify(catalog, never()).call(anyString(), eq("kex_forecast_metric"), anyMap());
    }

    @Test
    void refuse_un_catalogue_incomplet_ou_tronque() {
        for (Map<String, Object> payload : List.of(
                Map.of("data", List.of(metric()), "coverage", Map.of("complete", false), "warnings", List.of(), "truncated", false),
                Map.of("data", List.of(metric()), "coverage", Map.of("complete", true), "warnings", List.of(), "truncated", true))) {
            result("kex_list_forecastable_metrics", new McpToolResult("kafka-explorer", "catalog", false, List.of(), payload));
            assertThat(service.detail(ID).history().unavailable()).contains("complet");
        }
        verify(catalog, never()).call(anyString(), eq("kex_metric_history"), anyMap());
    }

    @Test
    void ne_masque_pas_les_erreurs_et_ne_divulgue_pas_les_secrets_transport() {
        when(catalog.call(anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("token=secret"));
        assertThat(service.metrics().unavailable()).contains("indisponible").doesNotContain("secret");
        assertThat(service.detail(ID).quality().unavailable()).isNotBlank();
    }

    @Test
    void connexion_absente_ne_contacte_pas_mcp() {
        for (String connection : new String[] {null, ""}) {
            var disabled = new ForecastViewService(catalog, new KafkaProperties(connection, "t", "l", 1));
            assertThat(disabled.metrics().unavailable()).contains("non configurée");
        }
        verify(catalog, never()).call(anyString(), anyString(), anyMap());
    }

    @Test
    void enveloppe_invalide_et_erreur_mcp_restent_indisponibles() {
        for (Object payload : List.of(Map.of(), List.of(), Map.of("data", List.of(metric())),
                Map.of("data", List.of(metric()), "coverage", Map.of(), "warnings", List.of(), "truncated", false))) {
            result("kex_list_forecastable_metrics", new McpToolResult("c", "t", false, List.of(), payload));
            assertThat(service.metrics().unavailable()).contains("invalide");
        }
        result("kex_list_forecastable_metrics", new McpToolResult("c", "t", true, List.of("secret"), null));
        assertThat(service.metrics().unavailable()).contains("refusée").doesNotContain("secret");
    }

    @Test
    void accepte_json_texte_et_ignore_les_blocs_non_json() {
        String json = "{\"data\":[],\"coverage\":{\"complete\":true},\"warnings\":[],\"truncated\":false}";
        result("kex_list_forecastable_metrics", new McpToolResult("c", "t", false,
                List.of("x".repeat(2_000_001), "note", "null", "[]", json), null));
        assertThat(service.metrics().data().isEmpty()).isTrue();
        result("kex_list_forecastable_metrics", new McpToolResult("c", "t", false, List.of("note"), null));
        assertThat(service.metrics().unavailable()).contains("invalide");
    }

    @Test
    void catalogue_malforme_ou_trop_grand_n_est_pas_une_liste_vide() {
        for (Object data : List.of(Map.of(), List.of(Map.of("seriesId", ID)),
                List.of(Map.of("seriesId", "", "metricId", "m", "environment", "test")),
                java.util.Collections.nCopies(257, metric()))) {
            respond("kex_list_forecastable_metrics", data);
            assertThat(service.metrics().unavailable()).contains("invalide");
        }
    }

    @Test
    void zero_depassement_est_distinct_d_une_lecture_impossible() {
        respond("kex_list_predicted_threshold_breaches", List.of());
        assertThat(service.breaches().data().isEmpty()).isTrue();
        respond("kex_list_predicted_threshold_breaches", Map.of());
        assertThat(service.breaches().unavailable()).contains("invalide");
        result("kex_list_predicted_threshold_breaches", new McpToolResult("c", "t", true, List.of(), null));
        assertThat(service.breaches().unavailable()).isNotBlank();
    }

    @Test
    void refuse_les_depassements_malformes_sans_les_presenter_comme_absence_de_risque() {
        for (Object row : List.of("bad", Map.of(), Map.of("threshold", Map.of()))) {
            respond("kex_list_predicted_threshold_breaches", List.of(row));
            assertThat(service.breaches().unavailable()).contains("invalide");
        }
        respond("kex_list_predicted_threshold_breaches", List.of(Map.of(
                "windowEndAt", 100L, "generatedAt", 1L,
                "threshold", Map.of("seriesId", ID, "breached", true, "threshold", 10,
                        "direction", "ABOVE", "visibility", "SHADOW"))));
        assertThat(service.breaches().unavailable()).isNull();
    }

    @Test
    void refuse_historique_sans_empreinte_ou_points_invalides() {
        respond("kex_list_forecastable_metrics", List.of(metric()));
        respond("kex_forecast_metric", Map.of("measured", false));
        respond("kex_get_forecast_quality", Map.of("measured", false));
        for (Object points : List.of(List.of(Map.of("endAt", "bad", "value", 1)),
                List.of(Map.of("endAt", 1, "value", "bad")),
                List.of(Map.of("endAt", 1, "value", 1), Map.of("endAt", 1, "value", 2)))) {
            respond("kex_metric_history", measured(Map.of("seriesId", ID, "inputFingerprint", "i",
                    "profileFingerprint", "p", "points", points)));
            assertThat(service.detail(ID).history().unavailable()).contains("historique invalide");
        }
        respond("kex_metric_history", measured(Map.of("seriesId", ID, "points", List.of())));
        assertThat(service.detail(ID).history().unavailable()).contains("historique invalide");
    }

    @Test
    void mesures_malformees_ne_deviennent_pas_zero() {
        respond("kex_list_forecastable_metrics", List.of(metric()));
        respond("kex_forecast_metric", measured(record(history(), forecast())));
        respond("kex_get_forecast_quality", Map.of("measured", false));
        for (Object data : List.of("text", Map.of("measured", "yes"), Map.of("measured", true),
                Map.of("measured", true, "value", 0))) {
            respond("kex_metric_history", data);
            assertThat(service.detail(ID).history().unavailable()).contains("mesure invalide");
        }
    }

    @Test
    void refuse_un_historique_d_une_autre_serie_ou_hors_limite() {
        respond("kex_list_forecastable_metrics", List.of(metric()));
        respond("kex_forecast_metric", Map.of("measured", false));
        respond("kex_get_forecast_quality", Map.of("measured", false));
        for (Object history : List.of(Map.of("seriesId", "other", "points", List.of()),
                Map.of("seriesId", ID, "points", "bad"),
                Map.of("seriesId", ID, "points", java.util.Collections.nCopies(513, Map.of())))) {
            respond("kex_metric_history", measured(history));
            assertThat(service.detail(ID).history().unavailable()).contains("historique invalide");
        }
    }

    @Test
    void refuse_prevision_hors_provenance_timestamps_non_croissants_et_quantiles_croises() {
        respond("kex_list_forecastable_metrics", List.of(metric()));
        respond("kex_metric_history", measured(history()));
        respond("kex_get_forecast_quality", measured(Map.of("evaluatedPoints", 60)));
        for (Object forecast : List.of("bad", Map.of("seriesId", "other", "points", List.of()),
                Map.of("seriesId", ID, "points", List.of(Map.of("at", 10, "central", 1, "q10", 3, "q50", 2, "q90", 1))),
                Map.of("seriesId", ID, "points", List.of(point(), point())),
                Map.of("seriesId", ID, "points", List.of(Map.of("at", "bad"))),
                Map.of("seriesId", ID, "points", List.of(Map.of("at", 10))))) {
            respond("kex_forecast_metric", measured(record(history(), forecast)));
            assertThat(service.detail(ID).forecast().unavailable()).contains("prévision invalide");
        }
        respond("kex_forecast_metric", measured(Map.of("context", history(), "state", "WARMING_UP",
                "strategy", "UNAVAILABLE", "visibility", "SHADOW")));
        assertThat(service.detail(ID).forecast().unavailable()).isNull();
        respond("kex_forecast_metric", measured(Map.of("state", "READY")));
        assertThat(service.detail(ID).forecast().unavailable()).isNotBlank();
    }

    private Map<String, Object> metric() {
        return Map.of("seriesId", ID, "metricId", "lag", "environment", "test", "unit", "records", "horizon", 60);
    }

    private Map<String, Object> history() {
        return Map.of("seriesId", ID, "inputFingerprint", "input", "profileFingerprint", "profile",
                "points", List.of(Map.of("endAt", 1, "value", 0, "imputed", false)));
    }

    private Map<String, Object> forecast() {
        return Map.of("seriesId", ID, "points", List.of(point()));
    }

    private Map<String, Object> point() {
        return Map.of("at", 100, "central", 0, "q10", 0, "q50", 0, "q90", 2);
    }

    private Map<String, Object> record(Object history, Object forecast) {
        return Map.of("state", "READY", "strategy", "TIMESFM", "visibility", "SHADOW", "context", history, "forecast", forecast);
    }

    private Map<String, Object> measured(Object value) {
        return Map.of("measured", true, "value", value);
    }

    private void respond(String tool, Object data) {
        result(tool, new McpToolResult("kafka-explorer", tool, false, List.of(),
                Map.of("data", data, "coverage", Map.of("complete", true), "warnings", List.of(), "truncated", false)));
    }

    private void result(String tool, McpToolResult result) {
        when(catalog.call(eq("kafka-explorer"), eq(tool), anyMap())).thenReturn(result);
    }
}
