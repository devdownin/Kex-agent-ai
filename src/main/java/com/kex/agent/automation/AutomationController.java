// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.automation;

import java.security.Principal;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent/automations")
@Profile("shared-memory")
@ConditionalOnProperty(prefix = "kex.agent.automation", name = "enabled", havingValue = "true")
class AutomationController {
    private final AutomationService service;
    AutomationController(AutomationService service) { this.service = service; }
    @GetMapping List<Automation> list(Principal p) { return service.list(p.getName()); }
    @GetMapping("/audit") List<AutomationAudit> audit(Principal p) { return service.audit(p.getName()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) Automation create(Principal p, @Valid @RequestBody AutomationRequest r) { return service.create(p.getName(), r); }
    @PutMapping("/{id}") Automation update(Principal p, @PathVariable String id, @Valid @RequestBody AutomationRequest r) { return service.update(p.getName(), id, r); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(Principal p, @PathVariable String id) { service.delete(p.getName(), id); }
}
