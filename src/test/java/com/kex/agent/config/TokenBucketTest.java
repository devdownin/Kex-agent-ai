package com.kex.agent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    /** 60/min = un jeton par seconde : assez lent pour que le test ne dépende pas de sa propre durée. */
    private static TokenBucket bucket(int capacity) {
        return new TokenBucket(capacity, 60);
    }

    @Test
    void autorise_la_pointe_puis_refuse() {
        TokenBucket bucket = bucket(3);

        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();
    }

    @Test
    void se_remplit_avec_le_temps() throws Exception {
        TokenBucket bucket = bucket(2);
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();

        Thread.sleep(1_100);

        assertThat(bucket.tryConsume()).isTrue();
    }

    @Test
    void ne_depasse_jamais_sa_capacite() throws Exception {
        TokenBucket bucket = bucket(2);

        Thread.sleep(1_100); // de quoi ajouter un jeton si la capacité n'était pas bornée

        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();
    }
}
