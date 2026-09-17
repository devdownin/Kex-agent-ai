// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class McpHealthCheckSchedulerTest {

    @Test
    void retente_l_initialisation_des_clients_muets() {
        McpToolCatalog catalog = mock(McpToolCatalog.class);

        new McpHealthCheckScheduler(catalog).retryUnreachable();

        verify(catalog).initializePending();
    }
}
