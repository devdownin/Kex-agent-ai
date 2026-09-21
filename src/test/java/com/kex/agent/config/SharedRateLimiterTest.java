// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le câblage, là où {@code JdbcRateLimiterTest} vérifie le partage lui-même : sous
 * {@code shared-memory}, le seau doit vivre dans une ligne que les répliques lisent, sinon la
 * propriété annonce un seuil que l'installation ne tient pas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:kex-debit;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.chat.memory.repository.jdbc.platform=h2"
})
@ActiveProfiles({"test", "shared-memory"})
class SharedRateLimiterTest {

    @Autowired
    RateLimiter rateLimiter;

    @Test
    void utilise_le_seau_partage() {
        assertThat(rateLimiter).isInstanceOf(JdbcRateLimiter.class);
        assertThat(rateLimiter.tryConsume("ops-console")).isTrue();
    }
}
