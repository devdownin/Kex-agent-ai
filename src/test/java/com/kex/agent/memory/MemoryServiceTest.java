// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-15T08:00:00Z"));

    private MemoryService service(int capacity, int maxContentLength, Duration retention) {
        return new MemoryService(new InMemoryMemoryRepository(capacity),
                new MemoryProperties(true, capacity, maxContentLength, retention), clock);
    }

    @Test
    void retient_un_fait_et_le_relit() {
        MemoryService service = service(200, 500, Duration.ofDays(30));

        service.remember("kafka-explorer expose kex_list_topics et kex_consumer_lag", "conv-1");

        assertThat(service.recall()).containsExactly("kafka-explorer expose kex_list_topics et kex_consumer_lag");
    }

    @Test
    void ignore_un_contenu_vide() {
        MemoryService service = service(200, 500, Duration.ofDays(30));

        String result = service.remember("   ", "conv-1");

        assertThat(result).isEqualTo("Rien à retenir : contenu vide.");
        assertThat(service.recall()).isEmpty();
    }

    @Test
    void tronque_un_contenu_trop_long() {
        MemoryService service = service(200, 10, Duration.ofDays(30));

        service.remember("un fait bien plus long que dix caractères", "conv-1");

        assertThat(service.recall()).singleElement().satisfies(content -> assertThat(content).hasSize(10));
    }

    @Test
    void evince_le_plus_ancien_au_dela_de_la_capacite() {
        MemoryService service = service(2, 500, Duration.ofDays(30));

        service.remember("A", "conv-1");
        service.remember("B", "conv-1");
        service.remember("C", "conv-1");

        // Plus récent en premier, comme l'audit : « A » est sorti, pas « B » qui suit « C ».
        assertThat(service.recall()).containsExactly("C", "B");
    }

    @Test
    void un_souvenir_perime_n_est_plus_relu() {
        MemoryService service = service(200, 500, Duration.ofDays(1));

        service.remember("le contact Kafka de l'équipe X est Bob", "conv-1");
        clock.advance(Duration.ofDays(2));

        assertThat(service.recall()).isEmpty();
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
