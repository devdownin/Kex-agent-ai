// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
public final class BudgetExceededException extends RuntimeException {
    public BudgetExceededException() { super("Budget insuffisant pour réserver cet appel"); }
}
