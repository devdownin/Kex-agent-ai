// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetServiceTest {

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }
    }

    @Test
    void jamais_epuise_sans_plafond_configure() {
        TokenBudgetService budget = new TokenBudgetService(new TokenBudgetProperties(0),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        budget.record(new AgentUsage(1_000_000, 1_000_000));

        assertThat(budget.exceeded()).isFalse();
    }

    @Test
    void s_epuise_au_plafond_configure() {
        TokenBudgetService budget = new TokenBudgetService(new TokenBudgetProperties(1_000),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        budget.record(new AgentUsage(600, 300));
        assertThat(budget.exceeded()).isFalse();

        budget.record(new AgentUsage(50, 50));
        assertThat(budget.exceeded()).isTrue();
        assertThat(budget.consumedToday()).isEqualTo(1_000);
    }

    /** Une mesure absente n'est jamais zéro : rien à ajouter, mais rien qui masque une panne. */
    @Test
    void ignore_un_usage_absent() {
        TokenBudgetService budget = new TokenBudgetService(new TokenBudgetProperties(1_000), Clock.systemUTC());

        budget.record(null);

        assertThat(budget.consumedToday()).isZero();
    }

    @Test
    void repart_a_zero_le_lendemain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-17T23:59:00Z"));
        TokenBudgetService budget = new TokenBudgetService(new TokenBudgetProperties(1_000), clock);

        budget.record(new AgentUsage(900, 0));
        assertThat(budget.exceeded()).isFalse();

        clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(budget.exceeded()).isFalse();
        assertThat(budget.consumedToday()).isZero();
    }
}
