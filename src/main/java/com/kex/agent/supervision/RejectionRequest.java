// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/** @param reason motif du refus, conservé dans l'audit : un refus sans raison ne s'explique plus */
public record RejectionRequest(String reason) {
}
