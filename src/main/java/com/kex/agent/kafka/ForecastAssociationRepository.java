// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;
import java.util.Map;

interface ForecastAssociationRepository {
    Map<String, List<ForecastAssociation>> all();
    // An empty list is a persisted override that disables configuration links for this process.
    void replace(String processId, List<ForecastAssociation> associations);
}
