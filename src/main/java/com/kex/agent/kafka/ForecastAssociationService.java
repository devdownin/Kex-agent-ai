// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;
import java.util.Map;

import com.kex.agent.supervision.SupervisionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class ForecastAssociationService {
    private final ForecastAssociationRepository repository;
    private final ForecastLinkProperties properties;
    private final ForecastViewService forecasts;
    private final SupervisionService supervision;

    ForecastAssociationService(ForecastAssociationRepository repository, ForecastLinkProperties properties,
                               ForecastViewService forecasts, SupervisionService supervision) {
        this.repository = repository; this.properties = properties; this.forecasts = forecasts; this.supervision = supervision;
    }

    record Associations(String processId, List<ForecastAssociation> associations, boolean overridden, String unavailable) { }
    record Row(ForecastAssociation association, ForecastViewService.Detail detail) { }
    record Summary(String processId, List<Row> forecasts, ForecastViewService.Read breaches,
                   boolean truncated, String unavailable, long readAt) { }

    List<ForecastAssociation> effective(String id, Map<String, List<ForecastAssociation>> saved) {
        if (saved.containsKey(id)) return saved.get(id);
        return properties.processLinks().stream().filter(link -> link.processIds().contains(id))
                .map(link -> new ForecastAssociation(link.seriesId(), link.environment())).distinct().toList();
    }

    List<String> processIds(String seriesId, String environment) {
        var saved = repository.all();
        return supervision.processes().stream().map(process -> process.id())
                .filter(id -> effective(id, saved).contains(new ForecastAssociation(seriesId, environment)))
                .limit(50).toList();
    }

    Associations read(String id) {
        requireProcess(id);
        var saved = repository.all();
        var links = effective(id, saved);
        if (links.isEmpty()) return new Associations(id, List.of(), saved.containsKey(id), null);
        var catalog = forecasts.metrics();
        if (!complete(catalog)) return new Associations(id, List.of(), saved.containsKey(id), "Catalogue autorisé indisponible ou incomplet");
        var allowed = links.stream().filter(link -> authorized(link, catalog)).toList();
        return new Associations(id, allowed, saved.containsKey(id), allowed.size() == links.size() ? null
                : "Certaines associations ne sont plus accessibles dans le catalogue autorisé");
    }

    Associations replace(String id, List<ForecastAssociation> links) {
        requireProcess(id);
        if (links.size() > 4 || links.stream().distinct().count() != links.size()
                || links.stream().anyMatch(link -> invalid(link.seriesId()) || invalid(link.environment()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Associations invalides ou dupliquées (maximum 4)");
        }
        if (!links.isEmpty()) {
            var catalog = forecasts.metrics();
            if (!complete(catalog)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catalogue autorisé indisponible ou incomplet");
            if (links.stream().anyMatch(link -> !authorized(link, catalog))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Série ou environnement absent du catalogue autorisé");
            }
        }
        repository.replace(id, links);
        return new Associations(id, List.copyOf(links), true, null);
    }

    Summary summary(String id) {
        var links = read(id);
        if (links.unavailable() != null) return new Summary(id, List.of(), null, false, links.unavailable(), System.currentTimeMillis());
        var rows = links.associations().stream().limit(4)
                .map(link -> new Row(link, forecasts.detail(link.seriesId(), link.environment()))).toList();
        return new Summary(id, rows, rows.isEmpty() ? null : forecasts.breaches(), links.associations().size() > 4,
                null, System.currentTimeMillis());
    }

    private boolean complete(ForecastViewService.Read catalog) {
        return catalog.unavailable() == null && !catalog.truncated() && catalog.data() != null
                && catalog.data().isArray() && catalog.coverage().path("complete").asBoolean(false);
    }

    private boolean authorized(ForecastAssociation link, ForecastViewService.Read catalog) {
        for (var metric : catalog.data()) {
            if (link.seriesId().equals(metric.path("seriesId").asText())
                    && link.environment().equals(metric.path("environment").asText())) return true;
        }
        return false;
    }

    private boolean invalid(String value) {
        return value == null || value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl);
    }

    private void requireProcess(String id) {
        if (supervision.processes().stream().noneMatch(process -> process.id().equals(id))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Processus inconnu");
        }
    }
}
