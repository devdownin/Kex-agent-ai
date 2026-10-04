// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.security.Principal;
import java.util.List;

import com.kex.agent.config.ActorIdentity;
import com.kex.agent.supervision.SupervisionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/agent/memory/summaries")
@ConditionalOnProperty(prefix = "kex.agent.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
class LongTermMemoryController {
    private final LongTermMemoryService memory;
    private final SupervisionService supervision;

    LongTermMemoryController(LongTermMemoryService memory, SupervisionService supervision) {
        this.memory = memory;
        this.supervision = supervision;
    }

    @GetMapping
    List<LearningEntry> list(Principal principal) {
        return memory.summaries(tenant(principal));
    }

    @PostMapping("/{id}/contradict")
    LearningEntry contradict(@PathVariable String id, @jakarta.validation.Valid @RequestBody Contradiction input,
            Principal principal) {
        LearningEntry result = memory.contradict(tenant(principal), id, actor(principal), input.sourceId(), input.reason());
        supervision.auditAction(actor(principal), "Résumé contredit et compétences retirées", id + " : " + input.reason());
        return result;
    }

    record Contradiction(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 255) String sourceId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 2000) String reason) {}

    @ExceptionHandler(IllegalStateException.class)
    org.springframework.http.ProblemDetail conflict(IllegalStateException ex) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void forget(@PathVariable String id, Principal principal) {
        if (!memory.forget(tenant(principal), id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        supervision.auditAction(actor(principal), "Mémoire durable supprimée", id);
    }

    private static String actor(Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return principal.getName();
    }

    /** À qui appartient le souvenir, là où {@link #actor} dit qui le supprime. */
    private static String tenant(Principal principal) {
        actor(principal);
        return ActorIdentity.tenantOf(principal);
    }
}
