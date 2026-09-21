// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Instant;

/**
 * La charte en vigueur : ce que l'exploitant veut que l'agent rappelle à chaque échange, en dehors
 * du prompt système figé au démarrage.
 *
 * @param markdown  texte en vigueur, vide tant que personne n'en a écrit
 * @param updatedBy qui l'a écrite ; {@code null} quand il n'y en a pas encore
 * @param reason    motif du dernier changement, conservé comme pour une politique
 */
public record Charter(String markdown, String updatedBy, Instant updatedAt, String reason) {
    public static final Charter NONE = new Charter("", null, null, null);

    public boolean present() {
        return markdown != null && !markdown.isBlank();
    }
}
