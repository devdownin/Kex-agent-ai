// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.server;

import com.kex.agent.supervision.Decision;
import com.kex.agent.supervision.DecisionStatus;
import com.kex.agent.supervision.SupervisionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Single mutation boundary for MCP. It never creates or bypasses decisions: only an already
 * pending decision can cross into the existing SupervisionService approval/rejection workflow.
 */
@Component
public class McpGovernedDecisionService {

    private final ObjectProvider<SupervisionService> supervision;
    private final KexMcpServerProperties properties;

    public McpGovernedDecisionService(ObjectProvider<SupervisionService> supervision,
                                      KexMcpServerProperties properties) {
        this.supervision = supervision;
        this.properties = properties;
    }

    public Decision approve(String decisionId, String actor) {
        return resolve(decisionId, actor, null, true);
    }

    public Decision reject(String decisionId, String actor, String reason) {
        return resolve(decisionId, actor, reason, false);
    }

    private Decision resolve(String decisionId, String actor, String reason, boolean approve) {
        if (!properties.governedMutationsEnabled()) {
            throw new IllegalStateException("Governed MCP mutations are disabled");
        }
        SupervisionService service = supervision.getIfAvailable();
        if (service == null) throw new IllegalStateException("Supervision is disabled");
        Decision current = service.decision(decisionId);
        if (current.status() != DecisionStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Decision is not pending human approval");
        }
        return approve ? service.approve(decisionId, actor) : service.reject(decisionId, reason, actor);
    }
}
