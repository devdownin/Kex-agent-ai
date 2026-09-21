// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.security.Principal;
import java.util.List;

import com.kex.agent.config.ActorIdentity;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent/automations")
@Profile("shared-memory")
@ConditionalOnProperty(prefix = "kex.agent.automation", name = "enabled", havingValue = "true")
class AutomationController {
    private final AutomationService service;
    AutomationController(AutomationService service) { this.service = service; }
    @GetMapping List<Automation> list(Principal p) { return service.list(ActorIdentity.tenantOf(p)); }
    @GetMapping("/audit") List<AutomationAudit> audit(Principal p) { return service.audit(ActorIdentity.tenantOf(p)); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) Automation create(Principal p, @Valid @RequestBody AutomationRequest r) { return service.create(ActorIdentity.tenantOf(p), r); }
    @PutMapping("/{id}") Automation update(Principal p, @PathVariable String id, @Valid @RequestBody AutomationRequest r) { return service.update(ActorIdentity.tenantOf(p), id, r); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(Principal p, @PathVariable String id) { service.delete(ActorIdentity.tenantOf(p), id); }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(UnknownAutomationException.class)
    ProblemDetail unknownAutomation(UnknownAutomationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
