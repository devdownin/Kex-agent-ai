// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.util.List;

import com.kex.agent.mcp.McpServerInfo;
import com.kex.agent.mcp.McpServerUnavailableException;
import com.kex.agent.mcp.McpStorageException;
import com.kex.agent.supervision.SupervisionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/agent/mcp/catalog")
public class McpCatalogController {

    private final McpRecommendedCatalog catalog;
    private final McpCatalogDiscoveryService discovery;
    private final ObjectProvider<SupervisionService> supervision;

    public McpCatalogController(McpRecommendedCatalog catalog, McpCatalogDiscoveryService discovery,
                                ObjectProvider<SupervisionService> supervision) {
        this.catalog = catalog;
        this.discovery = discovery;
        this.supervision = supervision;
    }

    @GetMapping
    List<McpCatalogEntry> list(Authentication authentication) {
        requireRole(authentication, "ROLE_OPERATOR", "ROLE_ADMIN");
        return catalog.entries();
    }

    @PostMapping("/{id}/install")
    @ResponseStatus(HttpStatus.CREATED)
    McpServerInfo install(@PathVariable String id, @Valid @RequestBody McpCatalogInstallRequest request,
                          Authentication authentication) {
        requireRole(authentication, "ROLE_ADMIN");
        McpServerInfo result = catalog.install(id, request);
        SupervisionService service = supervision.getIfAvailable();
        if (service != null) service.auditAction(authentication.getName(),
                "Installation depuis le catalogue MCP : " + id, "Connexion désactivée : " + request.connection());
        return result;
    }

    /**
     * Interroge les sources dynamiques activées ({@code kex.mcp.catalog.sources.*.enabled}) et
     * note chaque candidat. Une source désactivée ou injoignable le dit dans sa propre entrée,
     * jamais en silence : voir {@link McpCatalogSourceOverview}.
     */
    @GetMapping("/discover")
    List<McpCatalogSourceOverview> discover(Authentication authentication) {
        requireRole(authentication, "ROLE_OPERATOR", "ROLE_ADMIN");
        return discovery.discover();
    }

    @PostMapping("/discover/{sourceId}/{candidateId}/install")
    @ResponseStatus(HttpStatus.CREATED)
    McpServerInfo installDiscovered(@PathVariable String sourceId, @PathVariable String candidateId,
                                    @Valid @RequestBody McpCatalogInstallRequest request,
                                    Authentication authentication) {
        requireRole(authentication, "ROLE_ADMIN");
        McpServerInfo result = discovery.install(sourceId, candidateId, request);
        SupervisionService service = supervision.getIfAvailable();
        if (service != null) service.auditAction(authentication.getName(),
                "Installation depuis la découverte MCP (" + sourceId + "/" + candidateId + ")",
                "Connexion désactivée : " + request.connection());
        return result;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidConfiguration(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Configuration MCP invalide ou connexion déjà existante");
    }

    @ExceptionHandler({McpServerUnavailableException.class, McpStorageException.class})
    ProblemDetail unavailable(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Impossible d'installer cette connexion MCP");
    }

    private static void requireRole(Authentication authentication, String... roles) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (authentication.getAuthorities().stream().noneMatch(authority -> List.of(roles).contains(authority.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
