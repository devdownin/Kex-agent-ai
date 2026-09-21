// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deux dépôts sur la même base : deux répliques derrière un load balancer. C'est le seul montage
 * qui distingue un état partagé d'un état qui se trouvait être le même — {@code
 * InMemorySupervisionStateRepository} passerait chacune de ces assertions avec une seule instance,
 * et aucune avec deux.
 *
 * <p>Sur H2 plutôt qu'un contexte Spring complet, comme {@code JdbcAuditRepositoryTest} : ce qui
 * est en jeu est le SQL et le partage, pas le câblage du profil.
 */
class JdbcSupervisionStateRepositoryTest {

    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    private final SupervisionStateRepository premiere = repository();
    private final SupervisionStateRepository seconde = repository();

    private SupervisionStateRepository repository() {
        return new JdbcSupervisionStateRepository(jdbcTemplate, new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC), 3);
    }

    private static DataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    /**
     * Le défaut : la demande de validation naissait sur la réplique qui avait lancé le cycle, et
     * n'existait pour aucune autre. L'opérateur qui approuvait tombait une fois sur deux sur un
     * {@code 404} — et rien ne disait que ce n'était pas la décision qui avait expiré.
     */
    @Test
    void une_decision_creee_par_une_replique_est_approuvable_depuis_l_autre() {
        premiere.store(decision("d-1", DecisionStatus.PENDING_APPROVAL, null));

        assertThat(seconde.decision("d-1")).get()
                .extracting(Decision::status).isEqualTo(DecisionStatus.PENDING_APPROVAL);

        seconde.store(decision("d-1", DecisionStatus.EXECUTED, "Redémarré"));

        assertThat(premiere.decision("d-1")).get()
                .extracting(Decision::status, Decision::result)
                .containsExactly(DecisionStatus.EXECUTED, "Redémarré");
        // Trancher n'est pas créer : la décision reste une ligne, pas deux.
        assertThat(premiere.decisions()).hasSize(1);
    }

    @Test
    void conserve_les_observations_imbriquees_d_une_decision() {
        premiere.store(decision("d-1", DecisionStatus.PENDING_APPROVAL, null));

        assertThat(seconde.decision("d-1")).get()
                .extracting(entry -> entry.observations().getFirst().label())
                .isEqualTo("lag");
    }

    /**
     * Le verrou de {@link SupervisionScheduler} empêche deux cycles simultanés, pas un cycle que
     * quelqu'un croit avoir arrêté : sans pause partagée, l'agent mis en pause sur une réplique
     * continuait d'analyser et d'agir depuis les autres.
     */
    @Test
    void la_pause_vaut_pour_toutes_les_repliques() {
        assertThat(premiere.paused()).isFalse();

        premiere.paused(true);
        assertThat(seconde.paused()).isTrue();

        seconde.paused(false);
        assertThat(premiere.paused()).isFalse();
    }

    /** Déclarée sur une réplique, la fenêtre ne taisait les alertes que là. */
    @Test
    void une_fenetre_de_maintenance_vaut_pour_toutes_les_repliques() {
        Instant now = Instant.parse("2026-09-21T10:00:00Z");
        premiere.putMaintenance(new MaintenanceWindow("order-integration", "Commandes",
                now.plusSeconds(600), "déploiement", "ops-console"));

        assertThat(seconde.activeMaintenance(now)).singleElement()
                .extracting(MaintenanceWindow::declaredBy).isEqualTo("ops-console");
        assertThat(seconde.removeMaintenance("order-integration")).isNotNull();
        assertThat(premiere.activeMaintenance(now)).isEmpty();
    }

    @Test
    void une_fenetre_expiree_est_purgee_a_la_lecture() {
        Instant now = Instant.parse("2026-09-21T10:00:00Z");
        premiere.putMaintenance(new MaintenanceWindow("order-integration", "Commandes",
                now.plusSeconds(60), "déploiement", "ops-console"));

        assertThat(seconde.activeMaintenance(now.plusSeconds(61))).isEmpty();
    }

    @Test
    void redeclarer_une_fenetre_la_remplace_au_lieu_de_l_empiler() {
        Instant now = Instant.parse("2026-09-21T10:00:00Z");
        premiere.putMaintenance(new MaintenanceWindow("order-integration", "Commandes",
                now.plusSeconds(60), "déploiement", "ops-console"));
        seconde.putMaintenance(new MaintenanceWindow("order-integration", "Commandes",
                now.plusSeconds(600), "déploiement prolongé", "ci-pipeline"));

        assertThat(premiere.activeMaintenance(now)).singleElement()
                .extracting(MaintenanceWindow::reason).isEqualTo("déploiement prolongé");
    }

    /** Même raison que pour la mémoire : une fenêtre de lecture bornée ne borne pas la table. */
    @Test
    void borne_la_table_a_la_taille_d_historique() {
        Instant at = Instant.parse("2026-09-21T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            premiere.store(decision("d-" + i, DecisionStatus.BLOCKED, null, at.plusSeconds(i)));
        }

        assertThat(seconde.decisions()).extracting(Decision::id).containsExactly("d-4", "d-3", "d-2");
        assertThat(seconde.decision("d-0")).isEmpty();
    }

    /**
     * Le verrou par décision de {@code SupervisionService} vit dans le processus : il ne protège
     * plus rien maintenant qu'une décision est approuvable depuis n'importe quelle réplique. Deux
     * opérateurs sur deux écrans différents exécuteraient l'action deux fois — un redémarrage de
     * consumer, un rejeu de messages. C'est la base qui doit trancher la course.
     */
    @Test
    void une_decision_ne_se_reserve_qu_une_fois_toutes_repliques_confondues() {
        premiere.store(decision("d-1", DecisionStatus.PENDING_APPROVAL, null));

        assertThat(premiere.claim("d-1")).isTrue();
        assertThat(seconde.claim("d-1")).isFalse();
        assertThat(premiere.claim("d-1")).isFalse();
    }

    /** Réserver une décision inconnue échoue : il n'y a pas de ligne à marquer. */
    @Test
    void une_decision_inconnue_ne_se_reserve_pas() {
        assertThat(premiere.claim("jamais-vue")).isFalse();
    }

    private static Decision decision(String id, DecisionStatus status, String result) {
        return decision(id, status, result, Instant.parse("2026-09-21T10:00:00Z"));
    }

    private static Decision decision(String id, DecisionStatus status, String result, Instant at) {
        return new Decision(id, "cycle-1", "anomalie-1", "order-integration", "Commandes",
                Capability.RESTART_CONSUMER, "rattraper le retard", "lag en hausse",
                "Redémarrer le consumer", List.of(new Observation("lag", "200000")),
                "Faible", 0.91, status, result, "policy-v1", "corr-1", null, at, null,
                at.plusSeconds(1800));
    }
}
