// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Une sortie structurée est contrainte, pas garantie. Ces cas fixent ce qui arrive quand le modèle
 * rend autre chose que ce qu'on lui a demandé : le cycle continue, rien n'est deviné.
 */
class CycleAnalysisTest {

    private static final List<MonitoredProcess> KNOWN =
            List.of(new MonitoredProcess("orders", "Order Integration", null, null));

    private static final Instant NOW = Instant.parse("2026-09-15T05:32:14Z");

    @Test
    void le_schema_contraint_les_deux_listes_attendues() {
        Map<String, Object> schema = CycleAnalysis.schema();

        assertThat(schema).containsEntry("required", List.of("processes", "anomalies"));
        assertThat(schema.get("properties")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).containsKeys("processes", "anomalies");
    }

    @Test
    void une_reponse_vide_laisse_les_processus_en_inconnu() {
        List<ProcessSnapshot> snapshots = CycleAnalysis.snapshots(Map.of(), KNOWN);

        assertThat(snapshots).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.state()).isEqualTo(ProcessState.UNKNOWN);
            assertThat(snapshot.note()).isNotBlank();
        });
    }

    @Test
    void un_processus_invente_par_le_modele_n_entre_pas_dans_le_tableau() {
        Map<String, Object> answer = Map.of("processes", List.of(
                Map.of("processId", "inexistant", "state", "OK"),
                Map.of("processId", "orders", "state", "OK")));

        assertThat(CycleAnalysis.snapshots(answer, KNOWN))
                .singleElement().extracting(ProcessSnapshot::processId).isEqualTo("orders");
    }

    @Test
    void un_etat_illisible_devient_inconnu_plutot_que_sain() {
        Map<String, Object> answer = Map.of("processes",
                List.of(Map.of("processId", "orders", "state", "PRESQUE_OK", "lastRun", "hier")));

        assertThat(CycleAnalysis.snapshots(answer, KNOWN)).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.state()).isEqualTo(ProcessState.UNKNOWN);
            // Une date non ISO n'est pas une date : mieux vaut aucune valeur qu'une valeur fausse.
            assertThat(snapshot.lastRun()).isNull();
        });
    }

    @Test
    void une_anomalie_sans_titre_ou_sur_un_processus_inconnu_est_ecartee() {
        Map<String, Object> answer = Map.of("anomalies", List.of(
                Map.of("processId", "orders"),
                Map.of("processId", "inexistant", "title", "Retard"),
                Map.of("processId", "orders", "title", "Retard", "confidence", 0.7)));

        assertThat(CycleAnalysis.anomalies(answer, KNOWN, "cycle-1", NOW))
                .singleElement().extracting(Anomaly::title).isEqualTo("Retard");
    }

    @Test
    void une_gravite_absente_vaut_avertissement_jamais_silence() {
        Map<String, Object> answer = Map.of("anomalies",
                List.of(Map.of("processId", "orders", "title", "Retard", "severity", "OK")));

        assertThat(CycleAnalysis.anomalies(answer, KNOWN, "cycle-1", NOW))
                .singleElement().extracting(Anomaly::severity).isEqualTo(ProcessState.WARNING);
    }

    @Test
    void une_confiance_hors_bornes_ou_absente_est_ramenee_dans_l_intervalle() {
        Map<String, Object> high = Map.of("anomalies",
                List.of(Map.of("processId", "orders", "title", "A", "confidence", 3.2)));
        Map<String, Object> missing = Map.of("anomalies",
                List.of(Map.of("processId", "orders", "title", "A")));

        assertThat(CycleAnalysis.anomalies(high, KNOWN, "c", NOW).getFirst().confidence()).isEqualTo(1.0);
        assertThat(CycleAnalysis.anomalies(missing, KNOWN, "c", NOW).getFirst().confidence()).isZero();
    }

    @Test
    void une_capacite_inconnue_est_ignoree_plutot_que_rapprochee() {
        Map<String, Object> answer = Map.of("anomalies", List.of(
                Map.of("processId", "orders", "title", "A", "capability", "REBOOT_EVERYTHING")));

        assertThat(CycleAnalysis.anomalies(answer, KNOWN, "c", NOW).getFirst().capability()).isNull();
    }

    @Test
    void une_charge_utile_mal_typee_ne_fait_pas_echouer_le_cycle() {
        Map<String, Object> answer = new HashMap<>();
        answer.put("processes", "pas une liste");
        answer.put("anomalies", List.of("pas un objet", Map.of("processId", "orders", "title", "A",
                "observations", List.of("pas un objet", Map.of("label", "lag", "value", 12421)))));

        assertThat(CycleAnalysis.snapshots(answer, KNOWN)).hasSize(1);
        assertThat(CycleAnalysis.anomalies(answer, KNOWN, "c", NOW))
                .singleElement().extracting(Anomaly::observations)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Observation.class))
                .singleElement().isEqualTo(new Observation("lag", "12421"));
    }
}
