// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Seuils et listes qui pilotent {@link McpTrustScoreCalculator}. Aucun de ces seuils ne prétend
 * mesurer une propriété que les sources ne déclarent pas elles-mêmes ; ils ne font que trancher un
 * seuil sur une donnée réellement fournie (date de mise à jour, nombre de secrets requis, etc.) —
 * voir la javadoc de chaque critère dans {@link McpTrustScoreCalculator} pour ce qui reste
 * volontairement {@code UNKNOWN} faute de signal exploitable dans ce qu'exposent les sources
 * actuellement branchées (Docker MCP Catalog, registre officiel MCP).
 */
@ConfigurationProperties("kex.mcp.catalog.trust")
public record McpCatalogTrustProperties(
        /** Au-delà, « projet activement maintenu » n'est plus retenu. */
        @DefaultValue("180d") Duration maintenanceWindow,

        /** Secrets requis au-delà desquels « permissions minimales » n'est plus retenu. */
        @DefaultValue("2") int minimalPermissionsSecretThreshold,

        /** Secrets requis au-delà duquel l'installation est refusée, quel que soit le score. */
        @DefaultValue("5") int maxRequiredSecrets,

        /**
         * Fragments de nom de variable d'environnement (comparaison insensible à la casse) qui
         * signalent une demande disproportionnée à eux seuls, même un seul suffit à disqualifier.
         */
        @DefaultValue({"ROOT", "ADMIN", "MASTER_KEY", "PRIVATE_KEY", "SERVICE_ACCOUNT", "SUDO"})
        List<String> broadCredentialKeywords,

        /** Longueur de description en deçà de laquelle « outils documentés » n'est pas retenu. */
        @DefaultValue("40") int minDocumentedDescriptionLength,

        /** Tirages d'image en deçà duquel « réputation / adoption » n'est pas retenu. */
        @DefaultValue("1000") long minAdoptionPullCount,

        /**
         * Dépôts explicitement vérifiés par un opérateur (préfixe d'URL, ex.
         * {@code https://github.com/brave/}), seul moyen d'obtenir « éditeur officiel / identité
         * vérifiée » : aucune source consultée ne porte de statut d'éditeur vérifié, et le deviner
         * depuis un nom d'organisation GitHub inventerait une confiance que personne n'a accordée.
         * Vide par défaut.
         */
        @DefaultValue Set<String> trustedPublisherRepositories) {

    public McpCatalogTrustProperties {
        broadCredentialKeywords = broadCredentialKeywords == null ? List.of() : List.copyOf(broadCredentialKeywords);
        trustedPublisherRepositories = trustedPublisherRepositories == null
                ? Set.of() : Set.copyOf(trustedPublisherRepositories);
        if (maintenanceWindow == null || maintenanceWindow.isNegative() || maintenanceWindow.isZero()) {
            throw new IllegalArgumentException("kex.mcp.catalog.trust.maintenance-window doit être positif");
        }
        if (minimalPermissionsSecretThreshold < 0 || maxRequiredSecrets < 0) {
            throw new IllegalArgumentException("Les seuils de secrets requis doivent être positifs ou nuls");
        }
        if (minDocumentedDescriptionLength < 0 || minAdoptionPullCount < 0) {
            throw new IllegalArgumentException("Les seuils de documentation et d'adoption doivent être positifs ou nuls");
        }
    }
}
