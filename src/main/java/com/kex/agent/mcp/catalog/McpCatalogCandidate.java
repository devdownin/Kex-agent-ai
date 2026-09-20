// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.time.Instant;
import java.util.List;

/**
 * Un serveur MCP découvert par une {@link McpCatalogSource}, avant toute installation. Normalise
 * ce que chaque source expose réellement — jamais un champ complété par une valeur devinée : un
 * champ que la source ne fournit pas reste {@code null} ou vide, et {@link McpTrustScoreCalculator}
 * le lit comme « non mesuré », jamais comme un échec du critère qu'il concerne.
 *
 * @param sourceId identifiant de la source d'origine ({@link McpCatalogSource#id()})
 * @param id identifiant stable du candidat au sein de sa source
 * @param name nom technique (utilisé pour construire une commande d'installation)
 * @param title nom d'affichage, {@code null} si la source n'en distingue pas
 * @param description résumé fourni par la source, jamais réécrit
 * @param repositoryUrl dépôt source, {@code null} si non déclaré par la source
 * @param repositorySource hébergeur du dépôt (ex. {@code "github"}), {@code null} si inconnu
 * @param packages modes d'exécution locale déclarés (paquet géré, conteneur)
 * @param remotes points d'accès distants déclarés
 * @param updatedAt dernière mise à jour connue, {@code null} si non mesurée
 * @param status état déclaré par la source (ex. {@code "active"}, {@code "deprecated"}), {@code null} si non mesuré
 * @param pullCount adoption mesurée (tirages d'image), {@code null} si non mesurée
 * @param starCount adoption mesurée (étoiles), {@code null} si non mesurée
 */
public record McpCatalogCandidate(String sourceId, String id, String name, String title, String description,
                                  String repositoryUrl, String repositorySource, List<McpCatalogPackage> packages,
                                  List<McpCatalogRemote> remotes, Instant updatedAt, String status,
                                  Long pullCount, Long starCount) {
    public McpCatalogCandidate {
        packages = packages == null ? List.of() : List.copyOf(packages);
        remotes = remotes == null ? List.of() : List.copyOf(remotes);
    }
}
