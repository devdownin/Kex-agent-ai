// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.LearningRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CharterServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-21T10:00:00Z"));
    private final FakeRepository repository = new FakeRepository();
    private final CharterService charter = new CharterService(repository, clock);

    @Test
    void aucune_charte_tant_que_personne_n_en_a_ecrit() {
        assertThat(charter.current("ops").present()).isFalse();
        assertThat(charter.current("ops")).isEqualTo(Charter.NONE);
    }

    @Test
    void retient_la_derniere_version_avec_son_auteur_et_son_motif() {
        charter.update("ops", "Ne jamais redémarrer en heures ouvrées.", "alice", "consigne d'astreinte");

        Charter current = charter.current("ops");

        assertThat(current.markdown()).isEqualTo("Ne jamais redémarrer en heures ouvrées.");
        assertThat(current.updatedBy()).isEqualTo("alice");
        assertThat(current.reason()).isEqualTo("consigne d'astreinte");
        assertThat(current.present()).isTrue();
    }

    /** Une charte relue six mois plus tard sans ses versions antérieures ne s'explique pas. */
    @Test
    void conserve_les_versions_anterieures_de_la_plus_recente_a_la_plus_ancienne() {
        charter.update("ops", "première", "alice", "départ");
        clock.advance(Duration.ofHours(1));
        charter.update("ops", "seconde", "bob", "correction");

        assertThat(charter.versions("ops")).extracting(LearningEntry::markdown)
                .containsExactly("seconde", "première");
        assertThat(charter.current("ops").updatedBy()).isEqualTo("bob");
    }

    @Test
    void exige_un_motif_et_borne_la_taille() {
        assertThatThrownBy(() -> charter.update("ops", "texte", "alice", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> charter.update("ops", "x".repeat(8001), "alice", "trop long"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> charter.update("ops", null, "alice", "absente"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Chaque propriétaire a la sienne : la charte d'une équipe ne cadre pas le travail d'une autre. */
    @Test
    void ne_melange_pas_deux_proprietaires() {
        charter.update("ops", "consigne ops", "alice", "départ");

        assertThat(charter.current("data").present()).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class FakeRepository implements LearningRepository {
        private final List<LearningEntry> entries = new ArrayList<>();

        @Override
        public void add(LearningEntry entry) {
            entries.add(entry);
        }

        @Override
        public List<LearningEntry> list(String owner, String kind, Instant since) {
            return entries.stream()
                    .filter(entry -> entry.owner().equals(owner) && entry.kind().equals(kind))
                    .toList();
        }

        @Override
        public boolean review(String owner, String id, String status, String actor, Instant at, String reason) {
            return false;
        }


        @Override
        public java.util.Optional<String> ownerOf(String id) {
            return entries.stream().filter(entry -> entry.id().equals(id))
                    .map(LearningEntry::owner).findFirst();
        }

        @Override
        public boolean delete(String owner, String id) {
            return entries.removeIf(entry -> entry.owner().equals(owner) && entry.id().equals(id));
        }
    }
}
