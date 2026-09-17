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
            List.of(new MonitoredProcess("orders", "Order Integration", null, null, null));

    private static final Instant NOW = Instant.parse("2026-09-15T05:32:14Z");

    @Test
    void le_schema_contraint_les_deux_listes_attendues() {
        Map<String, Object> schema = CycleAnalysis.schema();

        assertThat(schema).containsEntry("required", List.of("processes", "anomalies"));
        assertThat(schema.get("properties")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).containsKeys("processes", "anomalies");
    }

    /* ── Couverture : un relevé partiel prouve une présence, jamais une absence ───────────── */

    @Test
    void un_ok_rendu_sur_une_passe_incomplete_redevient_inconnu() {
        // Le piège que cette correction ferme : l'anomalie était peut-être précisément dans ce qui
        // n'a pas été lu, et un cycle vert masquerait alors une panne.
        Map<String, Object> answer = Map.of("processes", List.of(Map.of(
                "processId", "orders", "state", "OK",
                "coverage", Map.of("complete", false, "stopReason", "TIME_BUDGET",
                        "notReached", List.of("demo.orders.3.enriched"),
                        "detail", "budget de 20 s épuisé"))));

        assertThat(CycleAnalysis.snapshots(answer, KNOWN)).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.state()).isEqualTo(ProcessState.UNKNOWN);
            assertThat(snapshot.coverage().knownIncomplete()).isTrue();
            assertThat(snapshot.coverage().stopReason()).isEqualTo(StopReason.TIME_BUDGET);
            // Nommé, pas compté : on doit pouvoir voir que le topic concerné y figure.
            assertThat(snapshot.coverage().notReached()).containsExactly("demo.orders.3.enriched");
        });
    }

    @Test
    void une_erreur_trouvee_sur_une_passe_incomplete_reste_une_erreur() {
        // Ce qui a été vu a bien été vu : l'incomplétude n'invalide qu'une conclusion négative.
        Map<String, Object> answer = Map.of("processes", List.of(Map.of(
                "processId", "orders", "state", "ERROR",
                "coverage", Map.of("complete", false, "stopReason", "PARTIAL_FAILURE"))));

        assertThat(CycleAnalysis.snapshots(answer, KNOWN)).singleElement()
                .extracting(ProcessSnapshot::state).isEqualTo(ProcessState.ERROR);
    }

    @Test
    void une_couverture_absente_ne_degrade_rien_mais_n_affirme_rien() {
        // La plupart des serveurs MCP ne portent pas d'enveloppe : tout basculer en UNKNOWN
        // rendrait le tableau de bord inutilisable partout ailleurs que devant Kafka Explorer.
        Map<String, Object> answer = Map.of("processes",
                List.of(Map.of("processId", "orders", "state", "OK")));

        assertThat(CycleAnalysis.snapshots(answer, KNOWN)).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.state()).isEqualTo(ProcessState.OK);
            assertThat(snapshot.coverage().stopReason()).isEqualTo(StopReason.NOT_REPORTED);
            assertThat(snapshot.coverage().complete()).isFalse();
            // Ni complet, ni déclaré incomplet : on ne conclut ni dans un sens ni dans l'autre.
            assertThat(snapshot.coverage().knownIncomplete()).isFalse();
        });
    }

    @Test
    void une_completude_affirmee_sans_exhausted_n_est_pas_une_completude() {
        // Le drapeau est une opinion du modèle ; le motif d'arrêt est ce que l'outil a rendu.
        Map<String, Object> answer = Map.of("processes", List.of(Map.of(
                "processId", "orders", "state", "OK",
                "coverage", Map.of("complete", true, "stopReason", "RECORD_LIMIT"))));

        assertThat(CycleAnalysis.snapshots(answer, KNOWN)).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.coverage().complete()).isFalse();
            assertThat(snapshot.state()).isEqualTo(ProcessState.UNKNOWN);
        });
    }

    @Test
    void une_passe_complete_laisse_l_etat_tel_quel() {
        Map<String, Object> answer = Map.of("processes", List.of(Map.of(
                "processId", "orders", "state", "OK",
                "coverage", Map.of("complete", true, "stopReason", "EXHAUSTED"))));

        assertThat(CycleAnalysis.snapshots(answer, KNOWN)).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.state()).isEqualTo(ProcessState.OK);
            assertThat(snapshot.coverage().complete()).isTrue();
        });
    }

    @Test
    void un_motif_d_arret_inconnu_ne_passe_pas_pour_une_passe_complete() {
        Map<String, Object> answer = Map.of("processes", List.of(Map.of(
                "processId", "orders", "state", "OK",
                "coverage", Map.of("complete", true, "stopReason", "TOUT_VA_BIEN",
                        "notReached", List.of("valide", 42)))));

        assertThat(CycleAnalysis.snapshots(answer, KNOWN)).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.coverage().stopReason()).isEqualTo(StopReason.NOT_REPORTED);
            assertThat(snapshot.coverage().complete()).isFalse();
            // Une entrée mal typée dans notReached est écartée, pas convertie.
            assertThat(snapshot.coverage().notReached()).containsExactly("valide");
        });
    }

    @Test
    void une_enveloppe_mal_typee_vaut_une_enveloppe_absente() {
        Map<String, Object> answer = Map.of("processes",
                List.of(Map.of("processId", "orders", "state", "WARNING", "coverage", "complète")));

        assertThat(CycleAnalysis.snapshots(answer, KNOWN)).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.coverage().stopReason()).isEqualTo(StopReason.NOT_REPORTED);
            assertThat(snapshot.state()).isEqualTo(ProcessState.WARNING);
        });
    }

    @Test
    void le_schema_exige_la_couverture_de_chaque_releve() {
        Map<String, Object> processes = nested(CycleAnalysis.schema(), "properties", "processes");
        Map<String, Object> item = cast(processes.get("items"));

        assertThat(item.get("required").toString()).contains("coverage");
        assertThat(cast(item.get("properties"))).containsKey("coverage");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> nested(Map<String, Object> source, String... keys) {
        Map<String, Object> current = source;
        for (String key : keys) {
            current = cast(current.get(key));
        }
        return current;
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
    void une_note_de_connaissance_citee_est_reprise_telle_quelle() {
        Map<String, Object> answer = Map.of("anomalies", List.of(
                Map.of("processId", "orders", "title", "Retard", "knowledgeReference", "Runbook-42")));

        assertThat(CycleAnalysis.anomalies(answer, KNOWN, "c", NOW))
                .singleElement().extracting(Anomaly::knowledgeReference).isEqualTo("Runbook-42");
    }

    @Test
    void l_absence_de_note_de_connaissance_reste_absente_jamais_inventee() {
        Map<String, Object> answer = Map.of("anomalies",
                List.of(Map.of("processId", "orders", "title", "Retard")));

        assertThat(CycleAnalysis.anomalies(answer, KNOWN, "c", NOW))
                .singleElement().extracting(Anomaly::knowledgeReference).isNull();
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
