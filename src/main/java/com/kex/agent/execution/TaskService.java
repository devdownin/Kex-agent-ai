// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.mcp.McpToolResult;
import com.kex.agent.supervision.Autonomy;
import com.kex.agent.supervision.SupervisionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import static com.kex.agent.execution.DurableTask.Status.*;
import static com.kex.agent.execution.DurableTask.StepStatus.PENDING;
import static com.kex.agent.execution.DurableTask.StepStatus.UNKNOWN;

/** Checkpoint before every effect. Unknown mutations are reconciled, never replayed. */
@Service
@ConditionalOnProperty(prefix = "kex.agent.tasks", name = "enabled", havingValue = "true")
public class TaskService {
    private final TaskRepository repository;
    private final TaskProperties properties;
    private final TaskGateway gateway;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SupervisionService supervision;
    private final Semaphore workers;
    private com.kex.agent.memory.LongTermMemoryService learning;
    private com.kex.agent.agent.TokenBudgetService budgets;
    @org.springframework.beans.factory.annotation.Autowired
    void budgets(com.kex.agent.agent.TokenBudgetService service) { budgets = service; }

    @org.springframework.beans.factory.annotation.Autowired
    void learning(org.springframework.beans.factory.ObjectProvider<com.kex.agent.memory.LongTermMemoryService> provider) {
        learning = provider.getIfAvailable();
    }

    public TaskService(TaskRepository repository, TaskProperties properties, TaskGateway gateway,
            ObjectMapper mapper, Clock clock, SupervisionService supervision) {
        this.repository = repository; this.properties = properties; this.gateway = gateway;
        this.mapper = mapper; this.clock = clock; this.supervision = supervision;
        this.workers = new Semaphore(properties.maxConcurrent());
    }

    public List<DurableTask> list(String owner) { return repository.list(owner); }
    public DurableTask get(String owner, String id) {
        return repository.find(owner, id).orElseThrow(() -> new TaskConflictException("Tâche inconnue"));
    }
    public Map<String, TaskProperties.Binding> bindings() { return properties.bindings(); }
    public int maxSteps() { return properties.maxSteps(); }
    public Map<String, Object> planningBindings() {
        if (properties.bindings().size() > 64) throw new IllegalArgumentException("Au plus 64 liaisons de tâche");
        Map<String, Object> descriptions = new java.util.TreeMap<>();
        properties.bindings().forEach((id, binding) -> descriptions.put(id, Map.of(
                "connection", binding.connection(), "tool", binding.tool(), "readOnly", binding.readOnly(),
                "capability", binding.capability() == null ? "READ" : binding.capability().name(),
                "inputSchema", gateway.schema(binding.connection(), binding.tool()))));
        if (json(descriptions).length() > 64000) throw new IllegalArgumentException("Catalogue de planification trop volumineux ; réduire les liaisons");
        return descriptions;
    }

    public DurableTask create(String owner, String actor, TaskPlan plan) {
        if (owner == null || owner.isBlank() || owner.length() > 255) throw new IllegalArgumentException("Locataire invalide");
        validate(plan);
        Instant now = clock.instant();
        DurableTask task = new DurableTask(UUID.randomUUID().toString(), owner, actor, 0, plan,
                fingerprint(plan), DRAFT, plan.steps().stream().map(step -> new DurableTask.StepResult(
                        step.id(), PENDING, null, null, null, null)).toList(),
                null, null, null, null, now, now, "Plan proposé ; aucune étape exécutée");
        repository.create(task);
        audit(actor, task, "Plan créé");
        return task;
    }

    public DurableTask approve(String owner, String id, String actor, boolean administrator) {
        DurableTask task = get(owner, id);
        if (task.status() != DRAFT) throw new TaskConflictException("Seul un brouillon peut être approuvé");
        validate(task.plan());
        if (!task.bindingFingerprint().equals(fingerprint(task.plan()))) throw new TaskConflictException("Les liaisons ont changé ; recréer le plan");
        if (!administrator && task.plan().steps().stream().anyMatch(s -> !binding(s).readOnly())) {
            throw new TaskConflictException("Une action exige l'approbation ADMIN du plan exact");
        }
        checkGovernance(task);
        DurableTask approved = copy(task, APPROVED, task.results(), actor, supervision.policy().version(),
                null, null, "Plan approuvé par " + actor);
        save(approved, task);
        audit(actor, approved, "Plan approuvé");
        return approved;
    }

    /** Claim synchronously, return immediately; the bounded worker outlives the HTTP connection. */
    public DurableTask start(String owner, String id, String actor) {
        if (!workers.tryAcquire()) throw new TaskConflictException("Nombre maximal de tâches actives atteint");
        try {
            DurableTask claimed = claim(owner, id);
            Thread.ofVirtual().name("kex-durable-task-" + id).start(() -> {
                try { execute(claimed, actor); }
                finally { workers.release(); }
            });
            return claimed;
        } catch (RuntimeException ex) { workers.release(); throw ex; }
    }

    /** Public deterministic seam used by restart and concurrency integration tests. */
    public DurableTask run(String owner, String id, String actor) { return execute(claim(owner, id), actor); }

    private DurableTask claim(String owner, String id) {
        DurableTask task = get(owner, id);
        if (task.leaseUntil() != null && task.leaseUntil().isAfter(clock.instant())) {
            throw new TaskConflictException("Une instance exécute déjà cette tâche");
        }
        if (!Set.of(APPROVED, PAUSED, NEEDS_RECONCILIATION, RUNNING).contains(task.status())) {
            throw new TaskConflictException("Tâche non reprenable dans cet état");
        }
        checkGovernance(task);
        List<DurableTask.StepResult> recovered = new ArrayList<>(task.results());
        for (int i = 0; i < recovered.size(); i++) {
            var result = recovered.get(i);
            if (result.status() == DurableTask.StepStatus.RUNNING) {
                boolean readOnly = binding(task.plan().steps().get(i)).readOnly();
                recovered.set(i, new DurableTask.StepResult(result.id(), readOnly ? PENDING : UNKNOWN,
                        result.output(), result.evidenceHash(), result.observedAt(),
                        readOnly ? "Lecture interrompue ; reprise autorisée" : "Effet incertain ; réconciliation requise"));
            }
        }
        DurableTask claimed = copy(task, RUNNING, recovered, task.approvedBy(), task.policyVersion(),
                UUID.randomUUID().toString(), clock.instant().plus(properties.runTimeout()), "Exécution ou reprise");
        save(claimed, task);
        return claimed;
    }

    public DurableTask cancel(String owner, String id, String actor) {
        DurableTask task = get(owner, id);
        if (Set.of(COMPLETED, VERIFIED, FAILED, CANCELLED).contains(task.status())) {
            throw new TaskConflictException("Tâche déjà terminée");
        }
        List<DurableTask.StepResult> results = new ArrayList<>(task.results());
        boolean uncertain = false;
        for (int i = 0; i < results.size(); i++) {
            var result = results.get(i);
            if (result.status() == DurableTask.StepStatus.RUNNING || result.status() == UNKNOWN) {
                boolean mutation = !binding(task.plan().steps().get(i)).readOnly();
                uncertain |= mutation;
                results.set(i, new DurableTask.StepResult(result.id(), mutation ? UNKNOWN : PENDING,
                        result.output(), result.evidenceHash(), result.observedAt(), "Interruption demandée"));
            }
        }
        DurableTask stopped = copy(task, uncertain ? NEEDS_RECONCILIATION : CANCELLED, results,
                task.approvedBy(), task.policyVersion(), null, uncertain ? task.leaseUntil() : null,
                uncertain ? "Action en cours : son effet doit être réconcilié" : "Tâche annulée");
        save(stopped, task);
        audit(actor, stopped, "Interruption demandée");
        return stopped;
    }

    private DurableTask execute(DurableTask claimed, String actor) {
        DurableTask task = claimed;
        for (int i = 0; i < task.plan().steps().size(); i++) {
            var prior = task.results().get(i);
            if (prior.status() == DurableTask.StepStatus.COMPLETED || prior.status() == DurableTask.StepStatus.VERIFIED) continue;
            if (!owned(task)) return get(task.owner(), task.id());
            try { checkGovernance(task); }
            catch (RuntimeException ex) { return finish(task, PAUSED, "Politique, contrat ou configuration indisponible ; réexaminer le plan", actor); }
            if (!clock.instant().isBefore(task.leaseUntil())) return finish(task, PAUSED, "Budget de durée atteint", actor);
            TaskPlan.Step step = task.plan().steps().get(i);
            TaskProperties.Binding binding = binding(step);
            boolean reconcile = prior.status() == UNKNOWN;
            if (reconcile && binding.verifier() == null) return finish(task, NEEDS_RECONCILIATION, "Aucun vérificateur configuré ; aucune action rejouée", actor);
            task = setStep(task, i, DurableTask.StepStatus.RUNNING, null, null,
                    reconcile && prior.observedAt() != null ? prior.observedAt() : clock.instant(),
                    reconcile ? "Réconciliation par lecture indépendante" : "Appel commencé ; point de reprise persisté");
            try {
                McpToolResult result;
                if (reconcile) {
                    result = verify(binding, task, step);
                } else {
                    Map<String, Object> arguments = new LinkedHashMap<>(step.arguments());
                    if (!binding.readOnly() && binding.idempotencyArgument() != null && !binding.idempotencyArgument().isBlank()) {
                        arguments.put(binding.idempotencyArgument(), task.id() + ":" + step.id());
                    }
                    result = call(binding, arguments, task);
                    // An explicit tool error proves failure; transport errors prove no absence of effect.
                    if (result.error()) {
                        task = setStep(task, i, DurableTask.StepStatus.FAILED, result, "L'outil a signalé un échec");
                        return finish(task, FAILED, "Étape en échec", actor);
                    }
                    if (!binding.readOnly()) {
                        task = setStep(task, i, DurableTask.StepStatus.RUNNING, result, "Action acceptée ; vérification indépendante");
                        result = verify(binding, task, step);
                    }
                }
                if (!owned(task)) return get(task.owner(), task.id());
                if (result == null) {
                    task = setStep(task, i, UNKNOWN, null, "Action acceptée sans vérificateur");
                    return finish(task, NEEDS_RECONCILIATION, "Résultat métier non vérifié", actor);
                }
                TaskPlan.Expectation expectation = binding.readOnly() ? step.expectation()
                        : new TaskPlan.Expectation(binding.verifier().pointer(), binding.verifier().expected());
                JsonNode proof = expectation == null ? null : evidence(result);
                JsonNode value = proof == null || expectation == null ? null : proof.at(expectation.pointer());
                if (result.error() || expectation != null && (value == null || value.isMissingNode() || value.isNull() || !value.isValueNode())
                        || !binding.readOnly() && !freshVerification(result, binding.verifier(), task.results().get(i).observedAt())) {
                    task = setStep(task, i, binding.readOnly() ? PENDING : UNKNOWN, result, "Preuve indisponible, incomplète ou non mesurée");
                    return finish(task, binding.readOnly() ? PAUSED : NEEDS_RECONCILIATION, "Résultat indéterminé ; aucune mutation rejouée", actor);
                }
                if (expectation != null && !matches(result, expectation)) {
                    task = setStep(task, i, DurableTask.StepStatus.FAILED, result, "Postcondition mesurée non satisfaite");
                    return finish(task, FAILED, "Objectif non atteint ; aucune mutation rejouée", actor);
                }
                task = setStep(task, i, expectation == null ? DurableTask.StepStatus.COMPLETED : DurableTask.StepStatus.VERIFIED,
                        result, expectation == null ? "Lecture terminée ; objectif non évalué" : "Postcondition vérifiée : " + expectation.pointer() + " = " + expectation.expected());
                audit(actor, task, "Étape " + step.id() + " : " + task.results().get(i).status());
            } catch (TaskConflictException ex) {
                return get(task.owner(), task.id());
            } catch (RuntimeException ex) {
                if (!owned(task)) return get(task.owner(), task.id());
                task = setStep(task, i, binding.readOnly() ? PENDING : UNKNOWN, null,
                        "Appel interrompu ou indisponible ; détail interne non exposé");
                return finish(task, binding.readOnly() ? PAUSED : NEEDS_RECONCILIATION,
                        "Interruption ; reprise des lectures ou réconciliation des actions", actor);
            }
        }
        boolean verified = task.results().getLast().status() == DurableTask.StepStatus.VERIFIED;
        return finish(task, verified ? VERIFIED : COMPLETED,
                verified ? "Critère final vérifié" : "Plan terminé ; objectif métier non vérifié", actor);
    }

    private McpToolResult verify(TaskProperties.Binding binding, DurableTask task, TaskPlan.Step step) {
        if (binding.verifier() == null) return null;
        var verifier = binding.verifier();
        TaskProperties.Binding read = properties.bindings().get(verifier.binding());
        if (read == null || !read.readOnly()) throw new IllegalArgumentException("Vérificateur non autorisé en lecture seule");
        Map<String, Object> args = new LinkedHashMap<>(verifier.arguments() == null ? Map.of() : verifier.arguments());
        // Trusted verifier arguments use explicit placeholders, never interpolate arbitrary tool output.
        args.replaceAll((key, value) -> "$taskId".equals(value) ? task.id() : "$stepId".equals(value) ? step.id() : value);
        return call(read, args, task);
    }

    private McpToolResult call(TaskProperties.Binding binding, Map<String, Object> arguments, DurableTask task) {
        long remaining = java.time.Duration.between(clock.instant(), task.leaseUntil()).toMillis();
        long timeout = Math.min(properties.callTimeout().toMillis(), remaining);
        if (timeout <= 0) throw new IllegalStateException("Budget de durée épuisé");
        TaskContracts.validate(gateway.schema(binding.connection(), binding.tool()), arguments);
        FutureTask<McpToolResult> future = new FutureTask<>(() -> {
            return gateway.call(binding.connection(), binding.tool(), arguments, budgets, task.owner(), task.id());
        });
        Thread.ofVirtual().start(future);
        try { return future.get(timeout, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); future.cancel(true); throw new IllegalStateException("Appel interrompu", ex); }
        catch (Exception ex) { future.cancel(true); throw new IllegalStateException("Appel indisponible", ex); }
    }

    private JsonNode evidence(McpToolResult result) {
        if (result.structuredContent() == null) return null;
        JsonNode node = mapper.valueToTree(result.structuredContent());
        if (!node.isObject()) return null;
        JsonNode coverage = node.path("coverage");
        if (!coverage.isMissingNode()) {
            if (!coverage.isObject() || !coverage.path("complete").isBoolean() || !coverage.path("complete").asBoolean()
                    || !"EXHAUSTED".equals(coverage.path("stopReason").asText())) return null;
            for (String field : List.of("topicsNotReached", "notReached")) {
                JsonNode missed = coverage.path(field);
                if (!missed.isMissingNode() && (!missed.isArray() || !missed.isEmpty())) return null;
            }
        }
        return node;
    }

    private boolean freshVerification(McpToolResult result, TaskProperties.Verifier verifier, Instant startedAt) {
        try {
            JsonNode json = evidence(result);
            JsonNode observed = json.at(verifier.observedAtPointer());
            Instant at = Instant.parse(observed.asText());
            if (at.isBefore(startedAt) || at.isAfter(clock.instant())) return false;
            return verifier.measuredPointer() == null || verifier.measuredPointer().isBlank()
                    || json.at(verifier.measuredPointer()).isBoolean() && json.at(verifier.measuredPointer()).asBoolean();
        } catch (Exception ex) { return false; }
    }

    private boolean matches(McpToolResult result, TaskPlan.Expectation expectation) {
        try {
            JsonNode json = evidence(result);
            if (json == null) return false;
            JsonNode value = json.at(expectation.pointer());
            JsonNode expected = mapper.valueToTree(expectation.expected());
            return !value.isMissingNode() && !value.isNull() && (value.isNumber() && expected.isNumber()
                    ? value.decimalValue().compareTo(expected.decimalValue()) == 0 : value.equals(expected));
        } catch (Exception ex) { return false; }
    }

    private void checkGovernance(DurableTask task) {
        if (supervision.status().state() == com.kex.agent.supervision.AgentState.PAUSED) throw new TaskConflictException("Agent en pause");
        if (!task.bindingFingerprint().equals(fingerprint(task.plan()))) throw new TaskConflictException("Liaisons modifiées ; recréer le plan");
        for (var step : task.plan().steps()) {
            var binding = binding(step);
            if (!binding.readOnly() && (binding.capability() == null
                    || supervision.policy().effectiveAutonomy(binding.capability()) == Autonomy.FORBIDDEN)) {
                throw new TaskConflictException("Capacité de tâche interdite par la politique actuelle");
            }
        }
    }

    public void validate(TaskPlan plan) {
        if (plan == null || plan.objective() == null || plan.objective().isBlank() || plan.objective().length() > 2000
                || plan.steps() == null || plan.steps().isEmpty() || plan.steps().size() > properties.maxSteps()
                || plan.preconditions() == null || plan.preconditions().size() > 16
                || plan.preconditions().stream().anyMatch(p -> p == null || p.length() > 1000)) {
            throw new IllegalArgumentException("Plan vide, invalide ou trop volumineux");
        }
        Set<String> prior = new HashSet<>();
        Set<String> signatures = new HashSet<>();
        for (var step : plan.steps()) {
            if (step == null || step.id() == null || !step.id().matches("[a-zA-Z0-9_-]{1,64}")
                    || prior.contains(step.id()) || step.description() == null || step.description().length() > 1000
                    || step.arguments() == null || step.dependsOn() == null || !prior.containsAll(step.dependsOn())) {
                throw new IllegalArgumentException("Étape invalide, dépendance future ou cycle");
            }
            TaskProperties.Binding binding = binding(step);
            if (binding.connection() == null || binding.tool() == null || binding.connection().isBlank() || binding.tool().isBlank()) throw new IllegalArgumentException("Liaison invalide");
            Map<String, Object> checkedArguments = new LinkedHashMap<>(step.arguments());
            if (!binding.readOnly() && binding.idempotencyArgument() != null && !binding.idempotencyArgument().isBlank()) {
                checkedArguments.put(binding.idempotencyArgument(), "00000000-0000-0000-0000-000000000000:" + step.id());
            }
            TaskContracts.validate(gateway.schema(binding.connection(), binding.tool()), checkedArguments);
            if (json(step.arguments()).length() > 8000) throw new IllegalArgumentException("Arguments trop volumineux");
            if (!signatures.add(step.binding() + canonical(step.arguments()))) throw new IllegalArgumentException("Étape répétée sans progrès ; préciser un plan différent");
            if (step.expectation() != null) validateExpectation(step.expectation());
            if (!binding.readOnly() && binding.capability() == null) throw new IllegalArgumentException("Capacité explicite requise pour une action");
            if (binding.verifier() != null) {
                var verifier = properties.bindings().get(binding.verifier().binding());
                if (verifier == null || !verifier.readOnly()
                        || verifier.connection().equals(binding.connection()) && verifier.tool().equals(binding.tool())) {
                    throw new IllegalArgumentException("Vérification indépendante en lecture seule requise");
                }
                validateExpectation(new TaskPlan.Expectation(binding.verifier().pointer(), binding.verifier().expected()));
            }
            prior.add(step.id());
        }
    }

    private void validateExpectation(TaskPlan.Expectation expectation) {
        if (expectation.pointer() == null || !expectation.pointer().startsWith("/") || expectation.pointer().length() > 512
                || expectation.expected() == null || !mapper.valueToTree(expectation.expected()).isValueNode()) {
            throw new IllegalArgumentException("Postcondition scalaire et pointeur JSON requis");
        }
        com.fasterxml.jackson.core.JsonPointer.compile(expectation.pointer());
    }
    private TaskProperties.Binding binding(TaskPlan.Step step) {
        var binding = properties.bindings().get(step.binding());
        if (binding == null) throw new IllegalArgumentException("Liaison de tâche non autorisée");
        return binding;
    }
    private String fingerprint(TaskPlan plan) {
        Map<String, TaskProperties.Binding> used = new java.util.TreeMap<>();
        for (var step : plan.steps()) {
            var binding = binding(step); used.put(step.binding(), binding);
            if (binding.verifier() != null) used.put(binding.verifier().binding(), properties.bindings().get(binding.verifier().binding()));
        }
        Map<String, Object> contracts = new java.util.TreeMap<>();
        used.forEach((id, binding) -> {
            if (binding == null) throw new IllegalArgumentException("Vérificateur absent");
            contracts.put(id, Map.of("binding", binding, "schema", gateway.schema(binding.connection(), binding.tool())));
        });
        return hash(canonical(contracts));
    }
    private String canonical(Object value) {
        return json(sorted(mapper.valueToTree(value)));
    }
    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var object = mapper.createObjectNode();
            var names = new java.util.TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> object.set(name, sorted(value.get(name)))); return object;
        }
        if (value.isArray()) { var array = mapper.createArrayNode(); value.forEach(item -> array.add(sorted(item))); return array; }
        return value;
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Contenu JSON invalide", ex); }
    }
    private static String hash(String text) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private boolean owned(DurableTask task) {
        var current = get(task.owner(), task.id());
        return current.revision() == task.revision() && current.status() == RUNNING && java.util.Objects.equals(current.worker(), task.worker());
    }
    private DurableTask setStep(DurableTask task, int index, DurableTask.StepStatus status, McpToolResult result, String detail) {
        String full = result == null ? null : result.structuredContent() == null ? String.join("\n", result.content()) : json(result.structuredContent());
        String output = full == null ? null : full.substring(0, Math.min(full.length(), properties.maxResultCharacters()));
        if (full != null && full.length() > properties.maxResultCharacters()) output += "\n[Résultat tronqué ; empreinte SHA-256 du résultat complet conservée]";
        return setStep(task, index, status, output, full == null ? null : hash(full), clock.instant(), detail);
    }
    private DurableTask setStep(DurableTask task, int index, DurableTask.StepStatus status, String output, String hash, Instant at, String detail) {
        var results = new ArrayList<>(task.results());
        results.set(index, new DurableTask.StepResult(task.plan().steps().get(index).id(), status, output, hash, at, detail));
        var next = copy(task, RUNNING, results, task.approvedBy(), task.policyVersion(), task.worker(), task.leaseUntil(), detail);
        save(next, task); return next;
    }
    private DurableTask finish(DurableTask task, DurableTask.Status status, String detail, String actor) {
        var next = copy(task, status, task.results(), task.approvedBy(), task.policyVersion(), null, null, detail);
        save(next, task); audit(actor, next, detail);
        if (learning != null && status == VERIFIED) learning.recordVerifiedTask(next);
        return next;
    }
    private DurableTask copy(DurableTask task, DurableTask.Status status, List<DurableTask.StepResult> results,
            String approvedBy, String policyVersion, String worker, Instant leaseUntil, String detail) {
        return new DurableTask(task.id(), task.owner(), task.actor(), task.revision() + 1, task.plan(), task.bindingFingerprint(),
                status, results, approvedBy, policyVersion, worker, leaseUntil, task.createdAt(), clock.instant(), detail);
    }
    private void save(DurableTask next, DurableTask before) {
        if (!repository.replace(next, before.revision())) throw new TaskConflictException("Tâche modifiée par une autre instance ; relire son état");
    }
    private void audit(String actor, DurableTask task, String detail) {
        supervision.auditAction(actor, "Tâche " + task.id(), detail);
    }
}
