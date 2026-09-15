// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/** Un fait relevé, pas une opinion : « 15 messages en attente ». Affiché avant toute analyse. */
public record Observation(String label, String value) {
}
