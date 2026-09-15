// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.List;

/**
 * @param unavailable raison pour laquelle le catalogue est vide — fournisseur sans catalogue,
 *                    passerelle injoignable, réponse illisible. Une liste vide sans motif se
 *                    lirait « aucun modèle », ce qui n'est jamais vrai
 * @param selected    le modèle configuré, même quand le catalogue ne le contient pas : c'est lui
 *                    qui répondra, que la passerelle l'annonce ou non
 */
public record LlmModels(List<LlmModel> models, String selected, String unavailable) {

    public static LlmModels unavailable(String selected, String reason) {
        return new LlmModels(List.of(), selected, reason);
    }
}
