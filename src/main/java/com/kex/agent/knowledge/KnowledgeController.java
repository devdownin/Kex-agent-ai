// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.knowledge;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conditionné sur la propriété et non sur la présence du service : une condition sur bean pour un
 * contrôleur dépend de l'ordre d'enregistrement, une condition sur propriété non.
 */
@RestController
@RequestMapping("/api/agent/knowledge")
@ConditionalOnProperty(prefix = "kex.agent.knowledge", name = "enabled", havingValue = "true")
class KnowledgeController {

    private final KnowledgeService knowledgeService;

    KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    List<String> add(@RequestBody @Valid @NotEmpty List<KnowledgeDocument> documents) {
        return knowledgeService.add(documents);
    }

    /** Même recherche que celle faite avant chaque échange : permet de voir ce que le modèle verra. */
    @GetMapping
    List<KnowledgeMatch> search(@RequestParam String query,
                                @RequestParam(required = false) Integer topK) {
        return knowledgeService.search(query, topK);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@RequestBody @NotEmpty List<String> ids) {
        knowledgeService.delete(ids);
    }
}
