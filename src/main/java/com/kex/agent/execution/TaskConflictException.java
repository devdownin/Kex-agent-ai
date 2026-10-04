// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.execution;

public class TaskConflictException extends RuntimeException {
    public TaskConflictException(String message) { super(message); }
}
