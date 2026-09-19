// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.scheduling.support.CronExpression;

/** Six-field Spring cron. A fixed second field guarantees no more than one run per minute. */
final class AutomationSchedule {
    private final CronExpression expression;
    private final ZoneId zone;

    AutomationSchedule(String cron, String zone) {
        if (cron == null || cron.length() > 120 || zone == null || zone.length() > 80) {
            throw new IllegalArgumentException("Cron et fuseau horaire requis");
        }
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 6 || !fields[0].matches("[0-5]?[0-9]")) {
            throw new IllegalArgumentException("Cron à six champs requis avec une seconde fixe (0..59)");
        }
        try {
            this.zone = ZoneId.of(zone);
            this.expression = CronExpression.parse(cron);
        }
        catch (RuntimeException ex) {
            throw new IllegalArgumentException("Cron ou fuseau horaire invalide");
        }
    }

    Instant next(Instant after) {
        ZonedDateTime next = expression.next(after.atZone(zone));
        if (next == null) {
            throw new IllegalArgumentException("Le cron ne possède aucune prochaine occurrence");
        }
        return next.toInstant();
    }
}
