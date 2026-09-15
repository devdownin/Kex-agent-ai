// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * En lecture seule, et ce n'est pas une étape manquante : le {@code ChatModel} est câblé au
 * démarrage du contexte. Rendre ces champs modifiables donnerait un écran qui accepte une valeur
 * sans que l'échange suivant en tienne compte — pire qu'un écran qui ne propose rien.
 */
@RestController
@RequestMapping("/api/agent/llm")
class LlmViewController {

    private final LlmViewService llm;

    LlmViewController(LlmViewService llm) {
        this.llm = llm;
    }

    @GetMapping
    LlmView llm() {
        return llm.describe();
    }
}
