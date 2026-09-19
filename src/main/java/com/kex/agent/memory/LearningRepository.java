// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.List;

public interface LearningRepository {
    void add(LearningEntry entry);
    List<LearningEntry> list(String owner, String kind, Instant since);
    /** Atomic transition. Only a pending skill owned by this principal may change. */
    boolean review(String owner, String id, String status, String actor, Instant at, String reason);
    boolean delete(String owner, String id);
}
