// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.kex.agent.testsupport.FlywayTestSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcAutomationRepositoryTest {

    private JdbcAutomationRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:test-automation-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        FlywayTestSchema.migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcAutomationRepository(jdbc);
    }

    @Test
    void performs_crud_due_claim_finish_and_audit() {
        Instant now = Instant.parse("2026-09-20T12:00:00Z");
        Instant next = now.plusSeconds(3600);

        AutomationRequest req = new AutomationRequest("Task 1", "prompt 1", "0 * * * * *", "UTC", true);

        Automation created = repository.create("user1", req, now, next);
        assertThat(created.name()).isEqualTo("Task 1");

        Automation found = repository.get("user1", created.id());
        assertThat(found.id()).isEqualTo(created.id());

        List<Automation> list = repository.list("user1");
        assertThat(list).hasSize(1);

        AutomationRequest updateReq = new AutomationRequest("Task 1 Updated", "prompt 1", "0 * * * * *", "UTC", true);
        Automation updated = repository.update("user1", created.id(), updateReq, now, next);
        assertThat(updated.name()).isEqualTo("Task 1 Updated");

        // Due and claim
        List<JdbcAutomationRepository.Candidate> due = repository.due(now.plusSeconds(4000), 10);
        assertThat(due).hasSize(1);

        Instant claimNow = now.plusSeconds(4000);
        Optional<JdbcAutomationRepository.Claim> claimOpt = repository.claim(due.get(0), claimNow, Duration.ofMinutes(2));
        assertThat(claimOpt).isPresent();

        JdbcAutomationRepository.Claim claim = claimOpt.get();
        repository.finish(claim, "SUCCEEDED", "Done result", now.plusSeconds(10));

        List<AutomationAudit> audit = repository.audit("user1");
        assertThat(audit).isNotEmpty();

        repository.delete("user1", created.id(), now.plusSeconds(20));
        assertThat(repository.list("user1")).isEmpty();
    }

    @Test
    void isole_les_automatisations_par_proprietaire() {
        Instant now = Instant.parse("2026-09-20T12:00:00Z");
        AutomationRequest req = new AutomationRequest("Task", "prompt", "0 * * * * *", "UTC", true);
        Automation owned = repository.create("user1", req, now, now.plusSeconds(60));
        repository.create("user2", req, now, now.plusSeconds(60));

        assertThat(repository.list("user1")).hasSize(1);
        assertThat(repository.list("user2")).hasSize(1);
        assertThatThrownBy(() -> repository.get("user2", owned.id()))
                .isInstanceOf(UnknownAutomationException.class);
        assertThatThrownBy(() -> repository.update("user2", owned.id(), req, now, now.plusSeconds(120)))
                .isInstanceOf(UnknownAutomationException.class);
        assertThatThrownBy(() -> repository.delete("user2", owned.id(), now))
                .isInstanceOf(UnknownAutomationException.class);
        assertThat(repository.audit("user2")).extracting(AutomationAudit::automationId)
                .doesNotContain(owned.id());
    }

    /**
     * Deux répliques lisant la même occurrence due ne doivent pas l'exécuter toutes les deux : le
     * CAS sur `revision` (voir {@code claim}) doit laisser une seule réclamation réussir.
     */
    @Test
    void une_seule_replique_reussit_a_reclamer_une_meme_occurrence() {
        Instant now = Instant.parse("2026-09-20T12:00:00Z");
        AutomationRequest req = new AutomationRequest("Task", "prompt", "0 * * * * *", "UTC", true);
        repository.create("user1", req, now, now);

        // Les deux répliques lisent le même instantané `due()`, avant que l'une des deux ne gagne.
        JdbcAutomationRepository.Candidate candidate = repository.due(now, 10).get(0);

        Optional<JdbcAutomationRepository.Claim> first = repository.claim(candidate, now, Duration.ofMinutes(2));
        Optional<JdbcAutomationRepository.Claim> second = repository.claim(candidate, now, Duration.ofMinutes(2));

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    /**
     * Un bail expiré permet à une autre réplique de réclamer la même automatisation avec un
     * nouveau `claim_id` ; l'ancienne réplique qui termine en retard, avec son jeton périmé, ne
     * doit pas écraser l'état de la réclamation en cours.
     */
    @Test
    void un_claim_id_perime_ne_change_rien_a_la_fin() {
        Instant now = Instant.parse("2026-09-20T12:00:00Z");
        AutomationRequest req = new AutomationRequest("Task", "prompt", "0 * * * * *", "UTC", true);
        Automation created = repository.create("user1", req, now, now);

        JdbcAutomationRepository.Claim stale = repository.claim(repository.due(now, 10).get(0),
                now, Duration.ofMinutes(2)).orElseThrow();

        // Le bail (2 min) est expiré : une seconde réplique réclame la même automatisation.
        Instant later = now.plusSeconds(4000);
        repository.claim(repository.due(later, 10).get(0), later, Duration.ofMinutes(2)).orElseThrow();

        int auditBefore = repository.audit("user1").size();
        repository.finish(stale, "SUCCEEDED", "résultat en retard", later.plusSeconds(10));

        assertThat(repository.audit("user1")).hasSize(auditBefore);
        assertThat(repository.get("user1", created.id()).status()).isEqualTo("RUNNING");
    }
}
