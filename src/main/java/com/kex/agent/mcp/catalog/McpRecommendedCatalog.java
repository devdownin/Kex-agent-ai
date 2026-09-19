// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kex.agent.mcp.McpServerInfo;
import com.kex.agent.mcp.McpServerRegistration;
import com.kex.agent.mcp.McpToolCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class McpRecommendedCatalog {

    private static final List<McpCatalogEntry> ENTRIES = List.of(
            new McpCatalogEntry("github-readonly", "GitHub (lecture seule)",
                    "Consulter les fichiers, tickets et pull requests avec un jeton GitHub à droits minimaux.",
                    "https://api.githubcopilot.com", "/mcp/readonly",
                    "https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md", true,
                    Set.of("get_file_contents", "issue_read", "pull_request_read", "search_repositories", "search_issues")),
            new McpCatalogEntry("microsoft-learn", "Microsoft Learn",
                    "Rechercher la documentation et des exemples de code publics Microsoft.",
                    "https://learn.microsoft.com", "/api/mcp",
                    "https://learn.microsoft.com/en-us/training/support/mcp-developer-reference", false,
                    Set.of("microsoft_docs_search", "microsoft_docs_fetch", "microsoft_code_sample_search")));

    private final McpToolCatalog catalog;

    public McpRecommendedCatalog(McpToolCatalog catalog) {
        this.catalog = catalog;
    }

    public List<McpCatalogEntry> entries() {
        return ENTRIES;
    }

    public McpServerInfo install(String id, McpCatalogInstallRequest request) {
        McpCatalogEntry entry = ENTRIES.stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown catalog entry"));
        if (request == null || request.connection() == null
                || !request.connection().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid connection name");
        }
        String token = request.bearerToken();
        if (entry.requiresToken() && (token == null || token.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This catalog entry requires a bearer token");
        }
        if (token != null && token.length() > 4096) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bearer token is too long");
        }
        if (!entry.requiresToken() && token != null && !token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This public catalog entry does not accept credentials");
        }
        // register performs a real handshake and persists only on success. The definition remains
        // disabled until an administrator explicitly enables it through the existing runtime API.
        return catalog.register(new McpServerRegistration(request.connection(), "HTTP", entry.url(),
                entry.endpoint(), token, Map.of(), null, List.of(), Map.of(), false,
                entry.allowedTools(), Map.of()));
    }
}
