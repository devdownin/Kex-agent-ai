// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

import java.util.List;
import java.util.Optional;

public interface TaskRepository {
    void create(DurableTask task);
    Optional<DurableTask> find(String owner, String id);
    List<DurableTask> list(String owner);
    /** Atomic across replicas for the JDBC implementation. */
    boolean replace(DurableTask task, long expectedRevision);
}
