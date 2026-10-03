// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;

import com.kex.agent.supervision.SupervisionService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Reads currently authorized provenance and explicit process associations, without inference. */
@Service
public class ForecastResourceService {
    private final ForecastViewService forecasts;
    private final ForecastLinkProperties properties;
    private final SupervisionService supervision;

    ForecastResourceService(ForecastViewService forecasts, ForecastLinkProperties properties,
                            SupervisionService supervision) {
        this.forecasts = forecasts;
        this.properties = properties;
        this.supervision = supervision;
    }

    public record Process(String id, String name) { }
    public record Resources(String seriesId, String environment, String definitionVersion,
                            List<String> topics, List<String> groups, List<Process> processes,
                            String unavailable) { }

    public Resources resources(String id) {
        var catalog = forecasts.metrics();
        if (catalog.unavailable() != null || catalog.truncated()
                || !catalog.coverage().path("complete").asBoolean(false)) {
            return failed(id, "Catalogue autorisé indisponible ou incomplet");
        }
        for (JsonNode metric : catalog.data()) {
            if (!id.equals(metric.path("seriesId").asText())) continue;
            JsonNode sources = metric.path("sources");
            if (!sources.isObject() || !sources.path("complete").isBoolean() || !sources.path("complete").asBoolean(false)
                    || !sources.path("definitionVersion").isTextual()
                    || sources.path("definitionVersion").asText().isBlank()
                    || !names(sources.path("topics")) || !names(sources.path("groups"))) {
                return failed(id, "Provenance non communiquée, masquée ou incomplète");
            }
            String environment = metric.path("environment").asText();
            var ids = properties.processLinks().stream()
                    .filter(link -> id.equals(link.seriesId()) && environment.equals(link.environment()))
                    .flatMap(link -> link.processIds().stream()).distinct().limit(50).toList();
            var processes = ids.isEmpty() ? List.<Process>of() : supervision.processes().stream()
                    .filter(process -> ids.contains(process.id()))
                    .map(process -> new Process(process.id(), process.name())).toList();
            return new Resources(id, environment, sources.path("definitionVersion").asText(),
                    strings(sources.path("topics")), strings(sources.path("groups")), processes, null);
        }
        return failed(id, "Série absente du catalogue autorisé");
    }

    private boolean names(JsonNode nodes) {
        if (!nodes.isArray() || nodes.size() > 128) return false;
        for (JsonNode node : nodes) {
            if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > 256
                    || node.asText().chars().anyMatch(Character::isISOControl)) return false;
        }
        return true;
    }

    private List<String> strings(JsonNode nodes) {
        return java.util.stream.StreamSupport.stream(nodes.spliterator(), false)
                .map(JsonNode::asText).distinct().toList();
    }

    private Resources failed(String id, String reason) {
        return new Resources(id, null, null, List.of(), List.of(), List.of(), reason);
    }
}
