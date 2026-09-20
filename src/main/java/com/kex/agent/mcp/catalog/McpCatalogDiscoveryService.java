// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kex.agent.mcp.McpServerInfo;
import com.kex.agent.mcp.McpServerRegistration;
import com.kex.agent.mcp.McpToolCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agrège les {@link McpCatalogSource} branchées, note chaque candidat avec
 * {@link McpTrustScoreCalculator} et installe via le même {@link McpToolCatalog#register} que
 * l'ajout manuel d'un serveur MCP — aucun chemin d'installation propre à la découverte.
 *
 * <p>L'éligibilité (critères éliminatoires) est revérifiée ici à l'installation, jamais héritée
 * d'un relevé antérieur côté client : un candidat récupéré une minute plus tôt a pu changer, et un
 * appelant ne doit jamais pouvoir installer ce qu'il n'a pas le droit de voir comme éligible.
 */
@Service
public class McpCatalogDiscoveryService {

    private final List<McpCatalogSource> sources;
    private final McpTrustScoreCalculator calculator;
    private final McpToolCatalog catalog;

    public McpCatalogDiscoveryService(List<McpCatalogSource> sources, McpTrustScoreCalculator calculator,
                                      McpToolCatalog catalog) {
        this.sources = List.copyOf(sources);
        this.calculator = calculator;
        this.catalog = catalog;
    }

    public List<McpCatalogSourceOverview> discover() {
        return sources.stream().map(this::describe).toList();
    }

    public McpServerInfo install(String sourceId, String candidateId, McpCatalogInstallRequest request) {
        McpCatalogCandidate candidate = fetchCandidate(sourceId, candidateId);
        McpTrustScore score = calculator.score(candidate);
        if (!score.eligible()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Candidat disqualifié : " + String.join(" ; ", score.disqualifiers()));
        }
        return catalog.register(toRegistration(candidate, request));
    }

    private McpCatalogSourceOverview describe(McpCatalogSource source) {
        if (!source.enabled()) return McpCatalogSourceOverview.disabled(source.id(), source.label());
        McpCatalogSourceResult result = source.fetch();
        if (result.error() != null) return McpCatalogSourceOverview.failed(source.id(), source.label(), result.error());
        List<McpCatalogDiscoveredCandidate> scored = result.candidates().stream()
                .map(candidate -> new McpCatalogDiscoveredCandidate(candidate, calculator.score(candidate)))
                .sorted(Comparator.comparingInt((McpCatalogDiscoveredCandidate entry) -> entry.score().total())
                        .reversed())
                .toList();
        return McpCatalogSourceOverview.ok(source.id(), source.label(), scored);
    }

    private McpCatalogCandidate fetchCandidate(String sourceId, String candidateId) {
        McpCatalogSource source = sources.stream().filter(candidate -> candidate.id().equals(sourceId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source de catalogue inconnue"));
        if (!source.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source de catalogue désactivée");
        }
        McpCatalogSourceResult result = source.fetch();
        if (result.error() != null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, result.error());
        return result.candidates().stream().filter(candidate -> candidate.id().equals(candidateId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidat introuvable"));
    }

    private static McpServerRegistration toRegistration(McpCatalogCandidate candidate, McpCatalogInstallRequest request) {
        if (!candidate.remotes().isEmpty()) return httpRegistration(candidate.remotes().get(0), request);
        String ociImage = ociImage(candidate);
        if (ociImage != null) return stdioRegistration(ociImage, request);
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Ce candidat n'est pas encore installable automatiquement : aucun point d'accès distant ni "
                        + "paquet de conteneur (OCI) déclaré");
    }

    private static String ociImage(McpCatalogCandidate candidate) {
        if ("docker".equals(candidate.sourceId())) return "mcp/" + candidate.name();
        return candidate.packages().stream().filter(pkg -> "oci".equalsIgnoreCase(pkg.registryType()))
                .map(McpCatalogPackage::identifier).findFirst().orElse(null);
    }

    private static McpServerRegistration httpRegistration(McpCatalogRemote remote, McpCatalogInstallRequest request) {
        if (remote.url() == null || remote.url().contains("{")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce point d'accès distant utilise un modèle d'URL non pris en charge");
        }
        URI uri;
        try {
            uri = URI.create(remote.url());
        }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL de point d'accès distant invalide");
        }
        if (uri.getQuery() != null || uri.getFragment() != null || uri.getAuthority() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "URL de point d'accès distant invalide : requête ou fragment non pris en charge");
        }
        String base = uri.getScheme() + "://" + uri.getAuthority();
        String path = uri.getRawPath();
        String endpoint = (path == null || path.isBlank()) ? "/mcp" : path;
        return new McpServerRegistration(request.connection(), "HTTP", base, endpoint, request.bearerToken(),
                Map.of(), null, List.of(), Map.of(), false, Set.of(), Map.of());
    }

    private static McpServerRegistration stdioRegistration(String image, McpCatalogInstallRequest request) {
        return new McpServerRegistration(request.connection(), "STDIO", null, null, null, Map.of(), "docker",
                List.of("run", "--rm", "-i", image), Map.of(), false, Set.of(), Map.of());
    }
}
