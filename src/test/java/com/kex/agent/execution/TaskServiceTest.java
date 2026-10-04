// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.mcp.McpToolResult;
import com.kex.agent.supervision.AgentState;
import com.kex.agent.supervision.AgentStatus;
import com.kex.agent.supervision.Autonomy;
import com.kex.agent.supervision.Capability;
import com.kex.agent.supervision.SupervisionPolicy;
import com.kex.agent.supervision.SupervisionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskServiceTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.parse("2026-10-04T05:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final SupervisionService supervision = mock(SupervisionService.class);
    private final SupervisionPolicy policy = mock(SupervisionPolicy.class);
    private final AgentStatus status = mock(AgentStatus.class);
    private final TaskProperties.Binding read = new TaskProperties.Binding("mcp", "read", true, null, null, null);
    private final TaskProperties.Binding mutation = new TaskProperties.Binding("mcp", "restart", false,
            Capability.RESTART_CONSUMER, "requestId", new TaskProperties.Verifier("read", Map.of(),
                    "/healthy", true, "/observedAt", "/measured"));

    private TaskService service(TaskRepository repository, TaskGateway gateway, Map<String, TaskProperties.Binding> bindings) {
        when(supervision.status()).thenReturn(status);
        when(status.state()).thenReturn(AgentState.OPERATIONAL);
        when(supervision.policy()).thenReturn(policy);
        when(policy.version()).thenReturn("v1");
        when(policy.effectiveAutonomy(Capability.RESTART_CONSUMER)).thenReturn(Autonomy.SUPERVISED);
        return new TaskService(repository, new TaskProperties(true, directory.toString(), 16, 2,
                Duration.ofMinutes(10), Duration.ofSeconds(10), 8000, bindings), gateway, json, clock, supervision);
    }
    private TaskPlan plan(String binding, TaskPlan.Expectation expectation) {
        return new TaskPlan("Rétablir le processus", List.of("Examiner le périmètre"),
                List.of(new TaskPlan.Step("check", "Étape", binding, Map.of("topic", "orders"), List.of(), expectation)));
    }
    private McpToolResult observed(boolean healthy) {
        return new McpToolResult("mcp", "read", false, List.of(), Map.of("healthy", healthy,
                "measured", true, "observedAt", now.toString(), "coverage", Map.of("complete", true, "stopReason", "EXHAUSTED")));
    }
    private DurableTask approved(TaskService service, String binding, TaskPlan.Expectation expectation) {
        var draft = service.create("team", "operator", plan(binding, expectation));
        return service.approve("team", draft.id(), "admin", true);
    }

    @Test void read_plan_survives_restart_and_does_not_claim_business_success_without_predicate() {
        var repository = new FileTaskRepository(json, directory);
        var service = service(repository, (c, t, a) -> observed(true), Map.of("read", read));
        var approved = approved(service, "read", null);
        var restarted = service(new FileTaskRepository(json, directory), (c, t, a) -> observed(true), Map.of("read", read));
        var completed = restarted.run("team", approved.id(), "operator");
        assertThat(completed.status()).isEqualTo(DurableTask.Status.COMPLETED);
        assertThat(new FileTaskRepository(json, directory).list("team")).hasSize(1);
        assertThat(restarted.list("other-team")).isEmpty();
        assertThatThrownBy(() -> restarted.get("other-team", approved.id())).isInstanceOf(TaskConflictException.class);
    }

    @Test void mutation_requires_admin_and_independent_fresh_measured_postcondition() {
        var count = new AtomicInteger();
        var service = service(new FileTaskRepository(json, directory), (c, t, a) -> {
            if (t.equals("restart")) {
                count.incrementAndGet();
                assertThat(a.get("requestId")).as("stable idempotency key").isNotNull();
                return new McpToolResult(c, t, false, List.of("accepted"), null);
            }
            return observed(true);
        }, Map.of("read", read, "restart", mutation));
        var draft = service.create("team", "operator", plan("restart", null));
        assertThatThrownBy(() -> service.approve("team", draft.id(), "operator", false)).isInstanceOf(TaskConflictException.class);
        service.approve("team", draft.id(), "admin", true);
        assertThat(service.run("team", draft.id(), "operator").status()).isEqualTo(DurableTask.Status.VERIFIED);
        assertThat(count).hasValue(1);
    }

    @Test void successful_transport_with_failed_postcondition_is_not_success() {
        var service = service(new FileTaskRepository(json, directory), (c, t, a) -> observed(false), Map.of("read", read));
        var approved = approved(service, "read", new TaskPlan.Expectation("/healthy", true));
        assertThat(service.run("team", approved.id(), "operator").status()).isEqualTo(DurableTask.Status.FAILED);
    }

    @Test void stale_and_incomplete_mutation_verification_cannot_confirm_recovery() {
        for (boolean stale : List.of(true, false)) {
            Path path = directory.resolve(stale ? "stale" : "partial");
            var service = service(new FileTaskRepository(json, path), (c, t, a) ->
                    new McpToolResult(c, t, false, List.of(), Map.of("healthy", true, "measured", true,
                            "observedAt", stale ? now.minusSeconds(1).toString() : now.toString(),
                            "coverage", Map.of("complete", stale, "stopReason", "EXHAUSTED"))), Map.of("read", read, "restart", mutation));
            var approved = approved(service, "restart", null);
            assertThat(service.run("team", approved.id(), "operator").status()).isEqualTo(DurableTask.Status.NEEDS_RECONCILIATION);
        }
    }

    @Test void uncertain_mutation_is_reconciled_without_replaying_effect_after_restart() {
        var repository = new FileTaskRepository(json, directory);
        var mutationCalls = new AtomicInteger();
        var verifierCalls = new AtomicInteger();
        var service = service(repository, (c, t, a) -> {
            if (t.equals("restart")) { mutationCalls.incrementAndGet(); throw new IllegalStateException("timeout after side effect"); }
            verifierCalls.incrementAndGet(); return observed(true);
        }, Map.of("read", read, "restart", mutation));
        var approved = approved(service, "restart", null);
        var interrupted = service.run("team", approved.id(), "operator");
        assertThat(interrupted.status()).isEqualTo(DurableTask.Status.NEEDS_RECONCILIATION);
        var restarted = service(new FileTaskRepository(json, directory), (c, t, a) -> {
            assertThat(t).isEqualTo("read"); verifierCalls.incrementAndGet(); return observed(true);
        }, Map.of("read", read, "restart", mutation));
        assertThat(restarted.run("team", approved.id(), "operator").status()).isEqualTo(DurableTask.Status.VERIFIED);
        assertThat(mutationCalls).hasValue(1);
        assertThat(verifierCalls).hasValue(1);
    }

    @Test void second_replica_cannot_claim_live_task() throws Exception {
        var repository = new FileTaskRepository(json, directory);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var service = service(repository, (c, t, a) -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return observed(true);
        }, Map.of("read", read));
        var approved = approved(service, "read", null);
        Thread worker = Thread.ofVirtual().start(() -> service.run("team", approved.id(), "operator"));
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            var second = service(repository, (c, t, a) -> { throw new AssertionError("must not execute"); }, Map.of("read", read));
            assertThatThrownBy(() -> second.run("team", approved.id(), "operator")).isInstanceOf(TaskConflictException.class);
        } finally { release.countDown(); worker.join(5000); }
    }

    @Test void cancellation_prevents_followup_calls_and_late_result_from_overwriting_state() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var service = service(new FileTaskRepository(json, directory), (c, t, a) -> {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return observed(true);
        }, Map.of("read", read));
        var plan = new TaskPlan("Read twice", List.of(), List.of(
                new TaskPlan.Step("one", "first", "read", Map.of("topic", "orders"), List.of(), null),
                new TaskPlan.Step("two", "second", "read", Map.of("topic", "other"), List.of("one"), null)));
        var task = service.create("team", "operator", plan); service.approve("team", task.id(), "operator", false);
        var worker = Thread.ofVirtual().start(() -> service.run("team", task.id(), "operator"));
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        service.cancel("team", task.id(), "operator"); release.countDown(); worker.join(5000);
        assertThat(service.get("team", task.id()).status()).isEqualTo(DurableTask.Status.CANCELLED);
        assertThat(calls).hasValue(1);
    }

    @Test void current_policy_revocation_and_binding_changes_prevent_execution() {
        var calls = new AtomicInteger();
        var repository = new FileTaskRepository(json, directory);
        var service = service(repository, (c, t, a) -> { calls.incrementAndGet(); return observed(true); }, Map.of("read", read, "restart", mutation));
        var approved = approved(service, "restart", null);
        when(policy.effectiveAutonomy(Capability.RESTART_CONSUMER)).thenReturn(Autonomy.FORBIDDEN);
        assertThatThrownBy(() -> service.run("team", approved.id(), "operator")).isInstanceOf(TaskConflictException.class);
        assertThat(calls).hasValue(0);
        var changed = service(repository, (c, t, a) -> { throw new AssertionError("changed tool must not execute"); },
                Map.of("read", read, "restart", new TaskProperties.Binding("mcp", "different-tool", false,
                        Capability.RESTART_CONSUMER, null, mutation.verifier())));
        assertThatThrownBy(() -> changed.run("team", approved.id(), "operator")).isInstanceOf(TaskConflictException.class);
    }

    @Test void rejects_unknown_bindings_cycles_and_repeated_calls_before_any_effect() {
        var service = service(new FileTaskRepository(json, directory), (c, t, a) -> { throw new AssertionError(); }, Map.of("read", read));
        assertThatThrownBy(() -> service.create("team", "operator", plan("unknown", null))).isInstanceOf(IllegalArgumentException.class);
        var cyclic = new TaskPlan("cycle", List.of(), List.of(new TaskPlan.Step("one", "bad", "read", Map.of(), List.of("two"), null)));
        assertThatThrownBy(() -> service.create("team", "operator", cyclic)).isInstanceOf(IllegalArgumentException.class);
        var repeated = new TaskPlan("loop", List.of(), List.of(new TaskPlan.Step("one", "one", "read", Map.of(), List.of(), null),
                new TaskPlan.Step("two", "two", "read", Map.of(), List.of("one"), null)));
        assertThatThrownBy(() -> service.create("team", "operator", repeated)).isInstanceOf(IllegalArgumentException.class);
    }
}
