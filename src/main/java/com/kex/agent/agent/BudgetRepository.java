// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.List;

public interface BudgetRepository {
    record Limit(String scope, long tokens, long costMicros) {}
    record Amount(long tokens, long costMicros) {}
    record Reservation(String id, List<String> scopes, long tokens, long costMicros) {
        public Reservation { scopes = List.copyOf(scopes); }
    }
    /** Atomically debit every scope or none. A process crash leaves the reservation charged. */
    Reservation reserve(String id, List<Limit> limits, long tokens, long costMicros);
    /** Idempotent settlement; an unknown/cancelled outcome keeps the conservative reservation. */
    void settle(Reservation reservation, long tokens, long costMicros);
    Amount used(String scope);
}
