// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.util.List;

/**
 * Un mode d'exécution déclaré par un candidat de catalogue (registre officiel MCP : npm, pypi,
 * cargo, oci, nuget, mcpb — voir le schéma {@code Package} de {@code registry.modelcontextprotocol.io}).
 * {@code registryType} vide signifie une commande locale sans paquet géré derrière — le signal même
 * que retient {@link McpTrustScoreCalculator} pour « exécution shell non justifiée ».
 */
public record McpCatalogPackage(String registryType, String identifier, String version, String runtimeHint,
                                List<String> runtimeArguments, List<String> packageArguments,
                                List<McpCatalogEnvironmentVariable> environmentVariables) {
    public McpCatalogPackage {
        runtimeArguments = runtimeArguments == null ? List.of() : List.copyOf(runtimeArguments);
        packageArguments = packageArguments == null ? List.of() : List.copyOf(packageArguments);
        environmentVariables = environmentVariables == null ? List.of() : List.copyOf(environmentVariables);
    }
}
