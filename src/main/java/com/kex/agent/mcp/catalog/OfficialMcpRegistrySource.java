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
 * {@code GET /v0/servers} — le registre officiel du protocole MCP, maintenu par le groupe de
 * pilotage MCP (schéma vérifié le 20/09/2026 contre {@code registry.modelcontextprotocol.io} et
 * l'OpenAPI publié dans {@code modelcontextprotocol/registry}). Registre volontairement neutre :
 * il indexe des métadonnées déclarées par les éditeurs, sans attestation de signature, SBOM ni
 * analyse de vulnérabilité — {@link McpTrustScoreCalculator} lit ces trois critères comme non
 * mesurés pour toute source qui ne les porte pas, celle-ci comprise.
 *
 * <p>Les noms des paramètres de pagination ({@code cursor}, {@code limit}) suivent la convention
 * REST standard pour une pagination par curseur ; seule la forme de la réponse
 * ({@code servers}/{@code metadata.nextCursor}) a été vérifiée en direct depuis cet environnement.
 */
@Component
class OfficialMcpRegistrySource implements McpCatalogSource {

    private static final Logger log = LoggerFactory.getLogger(OfficialMcpRegistrySource.class);
    private static final String DEFAULT_BASE_URL = "https://registry.modelcontextprotocol.io";
    private static final String STATUS_KEY = "io.modelcontextprotocol.registry/official";

    private final RestClient restClient;
    private final McpCatalogSourcesProperties.Source properties;

    OfficialMcpRegistrySource(RestClient.Builder builder, McpCatalogSourcesProperties sources) {
        this.properties = sources.officialRegistry();
        String baseUrl = StringUtils.hasText(properties.baseUrl()) ? properties.baseUrl() : DEFAULT_BASE_URL;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.requestTimeout());
        requestFactory.setReadTimeout(properties.requestTimeout());
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public String id() {
        return "official-registry";
    }

    @Override
    public String label() {
        return "Registre officiel MCP";
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
            String cursor = null;
            for (int page = 1; page <= properties.maxPages(); page++) {
                String pageCursor = cursor;
                Page response = restClient.get()
                        .uri(uriBuilder -> {
                            uriBuilder.path("/v0/servers").queryParam("limit", properties.pageSize());
                            if (pageCursor != null) uriBuilder.queryParam("cursor", pageCursor);
                            return uriBuilder.build();
                        })
                        .retrieve()
                        .body(Page.class);
                if (response == null || response.servers() == null || response.servers().isEmpty()) break;
                response.servers().stream()
                        // Un serveur déprécié ou supprimé n'a pas sa place dans une découverte de
                        // nouveaux candidats — le statut est un fait déclaré par le registre, pas
                        // une heuristique.
                        .filter(server -> status(server) == null || "active".equals(status(server)))
                        .forEach(server -> candidates.add(toCandidate(server)));
                cursor = response.metadata() == null ? null : response.metadata().nextCursor();
                if (cursor == null || cursor.isBlank()) break;
            }
            return McpCatalogSourceResult.ok(id(), label(), candidates);
        }
        catch (RestClientException ex) {
            log.warn("Registre officiel MCP injoignable : {}", ex.getMessage());
            return McpCatalogSourceResult.failed(id(), label(), "Registre officiel MCP injoignable : " + ex.getMessage());
        }
    }

    private static McpCatalogCandidate toCandidate(Server server) {
        List<McpCatalogPackage> packages = server.packages() == null ? List.of() : server.packages().stream()
                .map(OfficialMcpRegistrySource::toPackage).toList();
        List<McpCatalogRemote> remotes = server.remotes() == null ? List.of() : server.remotes().stream()
                .map(OfficialMcpRegistrySource::toRemote).toList();
        Meta.Official official = server.meta() == null ? null : server.meta().official();
        return new McpCatalogCandidate("official-registry", server.name(), server.name(), server.title(),
                server.description(), server.repository() == null ? null : server.repository().url(),
                server.repository() == null ? null : server.repository().source(), packages, remotes,
                official == null ? null : official.updatedAt(), official == null ? null : official.status(),
                null, null);
    }

    private static McpCatalogPackage toPackage(Package source) {
        List<McpCatalogEnvironmentVariable> variables = source.environmentVariables() == null ? List.of()
                : source.environmentVariables().stream().map(OfficialMcpRegistrySource::toVariable).toList();
        return new McpCatalogPackage(source.registryType(), source.identifier(), source.version(),
                source.runtimeHint(), source.runtimeArguments(), source.packageArguments(), variables);
    }

    private static McpCatalogEnvironmentVariable toVariable(KeyValueInput input) {
        return new McpCatalogEnvironmentVariable(input.name(), Boolean.TRUE.equals(input.isRequired()),
                Boolean.TRUE.equals(input.isSecret()), input.defaultValue());
    }

    private static McpCatalogRemote toRemote(Remote remote) {
        List<String> headerNames = remote.headers() == null ? List.of()
                : remote.headers().stream().map(KeyValueInput::name).toList();
        return new McpCatalogRemote(remote.type(), remote.url(), headerNames);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Page(List<Server> servers, Metadata metadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Metadata(@JsonProperty("nextCursor") String nextCursor, Integer count) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Server(String name, String title, String description, Repository repository,
                          List<Package> packages, List<Remote> remotes, @JsonProperty("_meta") Meta meta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Repository(String url, String source) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Package(@JsonProperty("registryType") String registryType, String identifier, String version,
                           @JsonProperty("runtimeHint") String runtimeHint,
                           @JsonProperty("runtimeArguments") List<String> runtimeArguments,
                           @JsonProperty("packageArguments") List<String> packageArguments,
                           @JsonProperty("environmentVariables") List<KeyValueInput> environmentVariables) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Remote(String type, String url, List<KeyValueInput> headers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KeyValueInput(String name, @JsonProperty("isRequired") Boolean isRequired,
                                 @JsonProperty("isSecret") Boolean isSecret,
                                 @JsonProperty("default") String defaultValue) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Meta(@JsonProperty(STATUS_KEY) Official official) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Official(String status, @JsonProperty("updatedAt") Instant updatedAt) {
        }
    }

    private static String status(Server server) {
        return server.meta() == null || server.meta().official() == null ? null : server.meta().official().status();
    }
}
