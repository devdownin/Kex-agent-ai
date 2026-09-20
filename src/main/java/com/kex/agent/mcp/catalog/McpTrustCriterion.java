// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

/** Grille de confiance sur 100, un poids fixe par critère — jamais recalculé à la volée. */
public enum McpTrustCriterion {
    OFFICIAL_PUBLISHER("Éditeur officiel / identité vérifiée", 20),
    VERIFIABLE_PROVENANCE("Provenance source → build vérifiable", 15),
    ARTIFACT_SIGNATURE("Signature / attestation de l'artefact", 10),
    SBOM_AVAILABLE("SBOM disponible", 10),
    DEPENDENCY_CVE_SCAN("Analyse CVE / dépendances", 10),
    ACTIVELY_MAINTAINED("Projet activement maintenu", 10),
    MINIMAL_PERMISSIONS("Permissions minimales", 10),
    DOCUMENTED_TOOLS("Outils et effets de bord documentés", 5),
    CONTAINER_ISOLATION("Isolation / conteneur disponible", 5),
    ADOPTION_REPUTATION("Réputation / adoption", 5);

    private final String label;
    private final int weight;

    McpTrustCriterion(String label, int weight) {
        this.label = label;
        this.weight = weight;
    }

    public String label() {
        return label;
    }

    public int weight() {
        return weight;
    }
}
