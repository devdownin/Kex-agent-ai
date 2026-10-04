// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.time.Instant;
import java.util.List;

public record DurableTask(String id, String owner, String actor, long revision, TaskPlan plan,
        String bindingFingerprint, Status status, List<StepResult> results,
        String approvedBy, String policyVersion, String worker, Instant leaseUntil,
        Instant createdAt, Instant updatedAt, String detail) {
    public enum Status { DRAFT, APPROVED, RUNNING, COMPLETED, VERIFIED, FAILED,
        PAUSED, NEEDS_RECONCILIATION, CANCELLED }
    public enum StepStatus { PENDING, RUNNING, COMPLETED, VERIFIED, FAILED, UNKNOWN }
    public record StepResult(String id, StepStatus status, String output, String evidenceHash,
            Instant observedAt, String detail) {}
    public DurableTask {
        results = List.copyOf(results);
    }
}
