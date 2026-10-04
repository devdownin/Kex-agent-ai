// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import com.kex.agent.config.ActorIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/tasks")
@ConditionalOnProperty(prefix = "kex.agent.tasks", name = "enabled", havingValue = "true")
class TaskController {
    private final TaskService tasks;
    private final TaskPlanner planner;
    TaskController(TaskService tasks, TaskPlanner planner) { this.tasks = tasks; this.planner = planner; }
    record PlanRequest(String objective, String previousTaskId) {}
    @GetMapping List<DurableTask> list(Principal actor) { return tasks.list(ActorIdentity.tenantOf(actor)); }
    @GetMapping("/{id}") DurableTask get(@PathVariable String id, Principal actor) {
        return tasks.get(ActorIdentity.tenantOf(actor), id);
    }
    @GetMapping("/bindings") Map<String, TaskProperties.Binding> bindings() { return tasks.bindings(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    DurableTask create(@RequestBody TaskPlan plan, Principal actor) {
        return tasks.create(ActorIdentity.tenantOf(actor), actor.getName(), plan);
    }
    @PostMapping("/plan") @ResponseStatus(HttpStatus.CREATED)
    DurableTask plan(@RequestBody PlanRequest request, Principal actor) {
        return planner.plan(ActorIdentity.tenantOf(actor), actor.getName(), request.objective(), request.previousTaskId());
    }
    @PostMapping("/{id}/approve") DurableTask approve(@PathVariable String id, Authentication actor) {
        boolean admin = actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return tasks.approve(ActorIdentity.tenantOf(actor), id, actor.getName(), admin);
    }
    @PostMapping("/{id}/run") @ResponseStatus(HttpStatus.ACCEPTED)
    DurableTask run(@PathVariable String id, Principal actor) {
        return tasks.start(ActorIdentity.tenantOf(actor), id, actor.getName());
    }
    @PostMapping("/{id}/cancel") DurableTask cancel(@PathVariable String id, Principal actor) {
        return tasks.cancel(ActorIdentity.tenantOf(actor), id, actor.getName());
    }
    @ExceptionHandler(TaskConflictException.class) ProblemDetail conflict(TaskConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
    @ExceptionHandler(IllegalArgumentException.class) ProblemDetail invalid(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
