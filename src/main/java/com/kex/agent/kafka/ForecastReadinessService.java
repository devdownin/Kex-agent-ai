// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.UnknownMcpServerException;
import org.springframework.stereotype.Service;

@Service
class ForecastReadinessService {
    private static final List<String> TOOLS = List.of("kex_list_forecastable_metrics", "kex_metric_history",
            "kex_forecast_metric", "kex_list_predicted_threshold_breaches", "kex_get_forecast_quality");
    private final McpToolCatalog catalog;
    private final KafkaProperties properties;
    private final ForecastViewService forecasts;

    ForecastReadinessService(McpToolCatalog catalog, KafkaProperties properties, ForecastViewService forecasts) {
        this.catalog = catalog; this.properties = properties; this.forecasts = forecasts;
    }

    record Check(String id, String state, String detail, String action) { }
    record Readiness(String connection, boolean connected, List<String> missingTools,
                     boolean catalogComplete, int seriesCount, boolean ready, String unavailable,
                     long checkedAt, List<Check> checks) { }

    Readiness read() {
        var checks = new ArrayList<Check>();
        java.util.Set<String> available;
        try {
            available = catalog.discoverTools(properties.connection()).stream()
                    .map(tool -> tool.name()).collect(Collectors.toSet());
        } catch (RuntimeException failure) {
            String detail = failure instanceof UnknownMcpServerException ? "Connexion MCP inconnue" : "Découverte MCP indisponible";
            checks.add(new Check("connection", "BLOCKED", detail, "Vérifier la connexion MCP configurée, son URL et son authentification."));
            TOOLS.forEach(tool -> checks.add(new Check(tool, "NOT_CHECKED", "Découverte non terminée", "Rétablir la connexion puis relancer le diagnostic.")));
            return result(false, List.of(), false, 0, false, detail, checks);
        }
        checks.add(new Check("connection", "READY", "Découverte fraîche réussie", ""));
        var missing = TOOLS.stream().filter(tool -> !available.contains(tool)).toList();
        boolean allowed = true;
        for (String tool : TOOLS) {
            if (!available.contains(tool)) {
                allowed = false;
                checks.add(new Check(tool, "MISSING", "Outil non annoncé par KafkaExplorer", "Activer le pilote de prévisions et mettre à jour KafkaExplorer."));
            } else if (!catalog.isToolAllowed(properties.connection(), tool)) {
                allowed = false;
                checks.add(new Check(tool, "BLOCKED", "Outil interdit par la politique de l’agent", "Autoriser cet outil dans la connexion MCP et la politique globale de l’agent."));
            } else checks.add(new Check(tool, "READY", "Outil annoncé et autorisé par l’agent", ""));
        }
        if (!allowed) return result(true, missing, false, 0, false, "Outils requis absents ou interdits", checks);
        var read = forecasts.metrics();
        boolean complete = read.unavailable() == null && !read.truncated() && read.coverage().path("complete").asBoolean(false);
        int count = complete ? read.data().size() : 0;
        boolean ready = complete && count > 0;
        checks.add(new Check("visible-series", ready ? "READY" : "BLOCKED",
                read.unavailable() != null ? read.unavailable() : !complete ? "Catalogue incomplet" : count == 0 ? "Aucune série autorisée visible" : count + " série(s) autorisée(s) visible(s)",
                ready ? "Les droits du serveur sur chaque source restent évalués lors de sa lecture." : "Vérifier les séries approuvées, les environnements, topics et groupes autorisés dans KafkaExplorer, puis relancer le diagnostic."));
        return result(true, missing, complete, count, ready, read.unavailable(), checks);
    }

    private Readiness result(boolean connected, List<String> missing, boolean complete, int count, boolean ready,
                             String unavailable, List<Check> checks) {
        return new Readiness(properties.connection(), connected, missing, complete, count, ready, unavailable,
                System.currentTimeMillis(), List.copyOf(checks));
    }
}
