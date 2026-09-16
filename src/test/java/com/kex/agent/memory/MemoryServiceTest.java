// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-15T08:00:00Z"));

    private MemoryService service(int capacity, int maxContentLength, Duration retention) {
        return new MemoryService(new InMemoryMemoryRepository(capacity),
                new MemoryProperties(true, capacity, maxContentLength, retention), clock);
    }

    private static List<String> contents(MemoryService service) {
        return service.recall().stream().map(MemoryFact::content).toList();
    }

    @Test
    void retient_un_fait_et_le_relit() {
        MemoryService service = service(200, 500, Duration.ofDays(30));

        service.remember("kafka-explorer expose kex_list_topics et kex_consumer_lag", null, "conv-1");

        assertThat(contents(service))
                .containsExactly("kafka-explorer expose kex_list_topics et kex_consumer_lag");
    }

    @Test
    void ignore_un_contenu_vide() {
        MemoryService service = service(200, 500, Duration.ofDays(30));

        String result = service.remember("   ", null, "conv-1");

        assertThat(result).isEqualTo("Rien à retenir : contenu vide.");
        assertThat(service.recall()).isEmpty();
    }

    @Test
    void tronque_un_contenu_trop_long() {
        MemoryService service = service(200, 10, Duration.ofDays(30));

        service.remember("un fait bien plus long que dix caractères", null, "conv-1");

        assertThat(contents(service)).singleElement().satisfies(content -> assertThat(content).hasSize(10));
    }

    @Test
    void evince_le_plus_ancien_au_dela_de_la_capacite() {
        MemoryService service = service(2, 500, Duration.ofDays(30));

        service.remember("A", null, "conv-1");
        service.remember("B", null, "conv-1");
        service.remember("C", null, "conv-1");

        // Plus récent en premier, comme l'audit : « A » est sorti, pas « B » qui suit « C ».
        assertThat(contents(service)).containsExactly("C", "B");
    }

    @Test
    void un_souvenir_perime_n_est_plus_relu() {
        MemoryService service = service(200, 500, Duration.ofDays(1));

        service.remember("le contact Kafka de l'équipe X est Bob", null, "conv-1");
        clock.advance(Duration.ofDays(2));

        assertThat(service.recall()).isEmpty();
    }

    @Test
    void une_correction_remplace_le_fait_devenu_faux() {
        MemoryService service = service(200, 500, Duration.ofDays(30));
        service.remember("la connexion MCP s'appelle kafka-explorer", null, "conv-1");
        String obsolete = service.recall().getFirst().id();

        String result = service.remember("la connexion MCP s'appelle kafka-explorer-v2", obsolete, "conv-2");

        assertThat(result).isEqualTo("Retenu, et le souvenir remplacé est marqué périmé.");
        // Le fait contredit ne doit plus être relu : les deux ensemble ne diraient pas lequel fait foi.
        assertThat(contents(service)).containsExactly("la connexion MCP s'appelle kafka-explorer-v2");
    }

    @Test
    void le_dit_quand_l_identifiant_a_remplacer_est_inconnu() {
        MemoryService service = service(200, 500, Duration.ofDays(30));

        String result = service.remember("un fait", "identifiant-inventé", "conv-1");

        // Le souvenir est écrit malgré tout : le perdre parce que l'identifiant est faux coûterait
        // plus que de le garder, mais le message doit dire que rien n'a été marqué périmé.
        assertThat(result)
                .isEqualTo("Retenu, mais aucun souvenir valable ne porte cet identifiant : rien n'a été marqué périmé.");
        assertThat(contents(service)).containsExactly("un fait");
    }

    @Test
    void ne_remarque_pas_un_souvenir_deja_remplace() {
        MemoryService service = service(200, 500, Duration.ofDays(30));
        service.remember("version 1", null, "conv-1");
        String first = service.recall().getFirst().id();
        service.remember("version 2", first, "conv-1");

        String result = service.remember("version 3", first, "conv-1");

        assertThat(result)
                .isEqualTo("Retenu, mais aucun souvenir valable ne porte cet identifiant : rien n'a été marqué périmé.");
        assertThat(contents(service)).containsExactly("version 3", "version 2");
    }

    /** Horloge pilotable : la péremption se teste en avançant, pas en attendant. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
