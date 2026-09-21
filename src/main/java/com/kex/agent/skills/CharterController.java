// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.security.Principal;
import java.util.List;

import com.kex.agent.config.ActorIdentity;
import com.kex.agent.memory.LearningEntry;
import com.kex.agent.supervision.SupervisionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/agent/charter")
@ConditionalOnProperty(prefix = "kex.agent.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
class CharterController {
    private final CharterService charter;
    private final SupervisionService supervision;

    CharterController(CharterService charter, SupervisionService supervision) {
        this.charter = charter;
        this.supervision = supervision;
    }

    @GetMapping
    Charter current(Principal principal) {
        return charter.current(tenant(principal));
    }

    /** L'historique, parce qu'une charte sans ses versions antérieures ne s'explique pas. */
    @GetMapping("/versions")
    List<LearningEntry> versions(Principal principal) {
        return charter.versions(tenant(principal));
    }

    @PutMapping
    Charter update(@Valid @RequestBody Update update, Principal principal) {
        String actor = actor(principal);
        Charter written = charter.update(tenant(principal), update.markdown(), actor, update.reason());
        supervision.auditAction(actor, "Charte mise à jour", update.reason());
        return written;
    }

    private static String actor(Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return principal.getName();
    }

    /**
     * La charte appartient à l'équipe, pas à l'administrateur qui l'a écrite : deux
     * administrateurs du même locataire écrivent la même, et l'audit garde lequel a signé.
     */
    private static String tenant(Principal principal) {
        actor(principal);
        return ActorIdentity.tenantOf(principal);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Motif obligatoire, comme pour une politique : sans lui, l'audit ne dit que « ça a changé ». */
    record Update(@Size(max = 8000) String markdown, @NotBlank @Size(max = 2000) String reason) { }
}
