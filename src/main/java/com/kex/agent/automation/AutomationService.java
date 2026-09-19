// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.kex.agent.memory.MemoryIdentity;

public final class AutomationService {
    private final JdbcAutomationRepository repository;
    private final AutomationProperties properties;
    private final Clock clock;

    public AutomationService(JdbcAutomationRepository repository, AutomationProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public Automation create(String owner, AutomationRequest request) {
        MemoryIdentity.require(owner);
        AutomationSchedule schedule = new AutomationSchedule(request.cron(), request.zone());
        Instant now = clock.instant();
        return repository.create(owner, request, now, schedule.next(now));
    }

    public Automation update(String owner, String id, AutomationRequest request) {
        MemoryIdentity.require(owner);
        AutomationSchedule schedule = new AutomationSchedule(request.cron(), request.zone());
        Instant now = clock.instant();
        return repository.update(owner, id, request, now, schedule.next(now));
    }

    public List<Automation> list(String owner) { return repository.list(MemoryIdentity.require(owner)); }
    public List<AutomationAudit> audit(String owner) { return repository.audit(MemoryIdentity.require(owner)); }
    public void delete(String owner, String id) { repository.delete(MemoryIdentity.require(owner), id, clock.instant()); }

    void tick(ScheduledTaskExecutor executor) {
        if (!properties.enabled()) return;
        Instant now = clock.instant();
        for (JdbcAutomationRepository.Candidate candidate : repository.due(now, properties.batchSize())) {
            repository.claim(candidate, now, properties.timeout()).ifPresent(claim -> {
                String result;
                String status;
                try {
                    result = executor.execute(claim.task().owner(), claim.task().id(), claim.task().prompt());
                    result = result == null ? "" : result.substring(0, Math.min(result.length(), properties.maxResultChars()));
                    status = "SUCCEEDED";
                }
                catch (RuntimeException ex) {
                    result = "Tâche échouée ; consulter l’audit.";
                    status = "FAILED";
                }
                repository.finish(claim, status, result, clock.instant());
            });
        }
    }
}
