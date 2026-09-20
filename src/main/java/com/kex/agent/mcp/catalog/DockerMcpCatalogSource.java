// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp.catalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@code GET /v2/repositories/mcp/} — l'API publique et non authentifiée de Docker Hub, filtrée sur
 * l'espace de nommage {@code mcp} que le MCP Catalog de Docker construit et publie lui-même. Chaque
 * image y est construite par Docker à partir du dépôt source soumis, jamais republiée telle quelle
 * depuis un artefact tiers — c'est ce que {@link McpTrustScoreCalculator#score} retient pour la
 * provenance des candidats de cette source, indépendamment du champ {@code repositoryUrl} que cette
 * API elle-même n'expose pas dans un relevé massif : la page renvoyée par Docker Hub ne porte que
 * l'image, l'adoption et la fraîcheur — pas le dépôt d'origine.
 *
 * <p>La pagination suit notre propre schéma ({@code page}/{@code page_size}), pas le champ
 * {@code next} renvoyé par Docker Hub : construire nous-mêmes chaque URI de page évite de faire
 * dépendre une boucle de requêtes du contenu d'une réponse déjà reçue.
 */
@Component
class DockerMcpCatalogSource implements McpCatalogSource {

    private static final Logger log = LoggerFactory.getLogger(DockerMcpCatalogSource.class);
    private static final String DEFAULT_BASE_URL = "https://hub.docker.com";

    private final RestClient restClient;
    private final McpCatalogSourcesProperties.Source properties;

    DockerMcpCatalogSource(RestClient.Builder builder, McpCatalogSourcesProperties sources) {
        this.properties = sources.docker();
        String baseUrl = StringUtils.hasText(properties.baseUrl()) ? properties.baseUrl() : DEFAULT_BASE_URL;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.requestTimeout());
        requestFactory.setReadTimeout(properties.requestTimeout());
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public String id() {
        return "docker";
    }

    @Override
    public String label() {
        return "Docker MCP Catalog";
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public McpCatalogSourceResult fetch() {
        if (!enabled()) return McpCatalogSourceResult.ok(id(), label(), List.of());
        List<McpCatalogCandidate> candidates = new ArrayList<>();
        try {
            for (int page = 1; page <= properties.maxPages(); page++) {
                Page response = restClient.get()
                        .uri("/v2/repositories/mcp/?page={page}&page_size={size}", page, properties.pageSize())
                        .retrieve()
                        .body(Page.class);
                if (response == null || response.results() == null || response.results().isEmpty()) break;
                response.results().forEach(result -> candidates.add(toCandidate(result)));
                if (response.next() == null) break;
            }
            return McpCatalogSourceResult.ok(id(), label(), candidates);
        }
        catch (RestClientException ex) {
            log.warn("Docker MCP Catalog injoignable : {}", ex.getMessage());
            return McpCatalogSourceResult.failed(id(), label(), "Docker MCP Catalog injoignable : " + ex.getMessage());
        }
    }

    private static McpCatalogCandidate toCandidate(Repository repository) {
        return new McpCatalogCandidate("docker", repository.name(), repository.name(), repository.name(),
                repository.description(), null, null, List.of(), List.of(), repository.lastUpdated(),
                repository.statusDescription(), repository.pullCount(), repository.starCount());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Page(Integer count, String next, List<Repository> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Repository(String name, String namespace, String description,
                              @JsonProperty("star_count") Long starCount,
                              @JsonProperty("pull_count") Long pullCount,
                              @JsonProperty("last_updated") Instant lastUpdated,
                              @JsonProperty("status_description") String statusDescription) {
    }
}
