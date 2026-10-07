// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.Map;

import com.kex.agent.mcp.McpServerUnavailableException;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolForbiddenException;
import com.kex.agent.mcp.McpToolResult;
import com.kex.agent.mcp.UnknownMcpServerException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Lectures des résultats autorisés par KafkaExplorer, sans SQL, inférence ni activation. */
@Service
public class ForecastViewService {
    private static final ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    private final McpToolCatalog catalog;
    private final KafkaProperties properties;

    ForecastViewService(McpToolCatalog catalog, KafkaProperties properties) {
        this.catalog = catalog;
        this.properties = properties;
    }

    public record Read(JsonNode data, JsonNode coverage, JsonNode warnings, boolean truncated,
                       String unavailable) {
        static Read failed(String reason) {
            return new Read(null, null, null, false, reason);
        }
    }

    public record Detail(String seriesId, Read forecast, Read history, Read quality) { }

    public Read metrics() {
        Read result = call("kex_list_forecastable_metrics", Map.of(), false);
        if (result.unavailable() != null) return result;
        if (!result.data().isArray() || result.data().size() > 256) {
            return Read.failed("Catalogue de prévisions invalide ou trop volumineux");
        }
        for (JsonNode metric : result.data()) {
            if (!metric.isObject() || !metric.path("seriesId").isTextual()
                    || metric.path("seriesId").asText().isBlank()
                    || !metric.path("metricId").isTextual() || !metric.path("environment").isTextual()) {
                return Read.failed("Catalogue de prévisions invalide");
            }
        }
        return result;
    }

    public Read breaches() {
        Read result = call("kex_list_predicted_threshold_breaches", Map.of(), false);
        if (result.unavailable() != null) return result;
        if (!result.data().isArray() || result.data().size() > 256) {
            return Read.failed("Liste de dépassements invalide ou trop volumineuse");
        }
        for (JsonNode row : result.data()) {
            JsonNode threshold = row.path("threshold");
            if (!row.isObject() || !row.path("windowEndAt").isIntegralNumber()
                    || !row.path("generatedAt").isIntegralNumber() || !threshold.isObject()
                    || !text(threshold.path("seriesId")) || !threshold.path("breached").asBoolean(false)
                    || !finite(threshold.path("threshold"))
                    || !java.util.Set.of("ABOVE", "BELOW").contains(threshold.path("direction").asText())
                    || !java.util.Set.of("SHADOW", "VISIBLE", "ACTIVE").contains(threshold.path("visibility").asText())) {
                return Read.failed("Liste de dépassements invalide");
            }
        }
        return result;
    }

    public Detail detail(String seriesId) { return detail(seriesId, null); }

    public Detail detail(String seriesId, String environment) {
        // Un identifiant saisi par le navigateur ne prouve pas l'autorisation de ses sources.
        // On exige sa présence dans le catalogue courant avant chaque lecture détaillée ; le
        // serveur vérifie à nouveau l'environnement, les topics et les groupes sur chaque outil.
        Read metrics = metrics();
        if (metrics.unavailable() != null) return unavailable(seriesId, metrics.unavailable());
        boolean allowed = false;
        for (JsonNode metric : metrics.data()) {
            if (metric.path("seriesId").asText().equals(seriesId)
                    && (environment == null || environment.equals(metric.path("environment").asText()))) allowed = true;
        }
        if (!allowed || metrics.truncated()
                || !metrics.coverage().path("complete").asBoolean(false)) {
            return unavailable(seriesId, "Série absente du catalogue autorisé complet");
        }
        Map<String, Object> arguments = Map.of("seriesId", seriesId);
        return new Detail(seriesId, call("kex_forecast_metric", arguments, true),
                call("kex_metric_history", arguments, true),
                call("kex_get_forecast_quality", arguments, true));
    }

    private Detail unavailable(String id, String reason) {
        Read failure = Read.failed(reason);
        return new Detail(id, failure, failure, failure);
    }

    private Read call(String tool, Map<String, Object> arguments, boolean measured) {
        if (properties.connection() == null || properties.connection().isBlank()) {
            return Read.failed("Connexion MCP KafkaExplorer non configurée");
        }
        try {
            McpToolResult result = catalog.call(properties.connection(), tool, arguments);
            if (result.error()) return Read.failed(diagnose(tool,
                    tool + " : lecture refusée ou indisponible sur « " + properties.connection()
                            + " ». Vérifiez les diagnostics MCP et le périmètre autorisé."));
            JsonNode payload = payload(result);
            if (payload == null || !payload.isObject() || !payload.hasNonNull("data")) {
                return Read.failed(tool + " : réponse invalide");
            }
            if (!payload.path("coverage").isObject()
                    || !payload.path("coverage").path("complete").isBoolean()
                    || !payload.path("warnings").isArray() || !payload.path("truncated").isBoolean()) {
                return Read.failed(tool + " : enveloppe de couverture invalide");
            }
            JsonNode data = payload.get("data");
            if (measured && (!data.isObject() || !data.path("measured").isBoolean()
                    || (data.path("measured").asBoolean() && !data.path("value").isObject()))) {
                return Read.failed(tool + " : mesure invalide");
            }
            if (measured && data.path("measured").asBoolean()) {
                JsonNode value = data.path("value");
                String id = (String) arguments.get("seriesId");
                if (tool.equals("kex_metric_history") && !validHistory(value, id)) {
                    return Read.failed(tool + " : historique invalide ou provenance incohérente");
                }
                if (tool.equals("kex_forecast_metric") && (!value.path("state").isTextual()
                        || !value.path("strategy").isTextual() || !value.path("visibility").isTextual()
                        || !validHistory(value.path("context"), id)
                        || !validForecast(value.path("forecast"), id))) {
                    return Read.failed(tool + " : prévision invalide ou provenance incohérente");
                }
            }
            return new Read(data, payload.path("coverage"), payload.path("warnings"),
                    payload.path("truncated").asBoolean(false), null);
        }
        catch (UnknownMcpServerException ex) {
            return Read.failed("Connexion MCP « " + properties.connection()
                    + " » introuvable. Vérifiez kex.agent.kafka.connection et la connexion dans la page MCP.");
        }
        catch (McpToolForbiddenException ex) {
            return Read.failed("Accès refusé à " + tool + " sur « " + properties.connection()
                    + " ». Vérifiez la politique d'accès et autorisez cet outil si nécessaire.");
        }
        catch (McpServerUnavailableException ex) {
            return Read.failed("Serveur MCP « " + properties.connection()
                    + " » indisponible. Vérifiez son état, son URL et son authentification dans la page MCP.");
        }
        catch (Exception ex) {
            // Ne pas transmettre les détails d'une exception transport : ils peuvent contenir
            // des paramètres de connexion. L'état est visible et les diagnostics MCP restent disponibles.
            return Read.failed(diagnose(tool, tool + " : Erreur de récupération sur « " + properties.connection()
                    + " », lecture indisponible. Vérifiez les diagnostics MCP puis réessayez."));
        }
    }

    private String diagnose(String tool, String fallback) {
        // Une exception d'appel ne prouve pas l'absence d'un outil. Seul le catalogue d'un
        // serveur initialisé permet de proposer l'activation du pilote plutôt qu'un réessai.
        try {
            if (!catalog.announcesTool(properties.connection(), tool)) {
                return tool + " : outil non annoncé par « " + properties.connection()
                        + " ». Vérifiez la version de KafkaExplorer et activez explorer.forecasting.pilot.enabled=true"
                        + " avec l'historique, l'inférence et les séries approuvées configurés."
                        + " Redémarrez KafkaExplorer puis actualisez son catalogue MCP.";
            }
        }
        catch (RuntimeException ignored) { /* Le diagnostic ne doit pas masquer l'échec initial. */ }
        return fallback;
    }

    private boolean validHistory(JsonNode history, String id) {
        if (!history.isObject() || !history.path("seriesId").asText().equals(id)
                || !history.path("inputFingerprint").isTextual() || !text(history.path("profileFingerprint"))
                || !history.path("points").isArray() || history.path("points").size() > 512) return false;
        long previous = Long.MIN_VALUE;
        for (JsonNode point : history.path("points")) {
            if (!point.isObject() || !point.path("endAt").isIntegralNumber()
                    || point.path("endAt").asLong() <= previous
                    || (!point.path("value").isNull() && !finite(point.path("value")))) return false;
            previous = point.path("endAt").asLong();
        }
        return true;
    }

    private boolean text(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank();
    }

    private boolean finite(JsonNode value) {
        return value.isNumber() && Double.isFinite(value.asDouble());
    }

    private boolean validForecast(JsonNode forecast, String id) {
        if (forecast.isMissingNode() || forecast.isNull()) return true;
        if (!forecast.isObject() || !forecast.path("seriesId").asText().equals(id)
                || !forecast.path("points").isArray() || forecast.path("points").size() > 512) return false;
        long previous = Long.MIN_VALUE;
        for (JsonNode point : forecast.path("points")) {
            if (!point.path("at").isIntegralNumber() || point.path("at").asLong() <= previous) return false;
            previous = point.path("at").asLong();
            for (String name : new String[] {"central", "q10", "q50", "q90"}) {
                if (!point.path(name).isNumber() || !Double.isFinite(point.path(name).asDouble())) return false;
            }
            if (point.path("q10").asDouble() > point.path("q50").asDouble()
                    || point.path("q50").asDouble() > point.path("q90").asDouble()) return false;
        }
        return true;
    }

    private JsonNode payload(McpToolResult result) throws Exception {
        if (result.structuredContent() != null) return JSON.valueToTree(result.structuredContent());
        for (String block : result.content()) {
            if (block.length() > 2_000_000) continue;
            try {
                JsonNode candidate = JSON.readTree(block);
                if (candidate != null && candidate.isObject()) return candidate;
            }
            catch (Exception ignored) {
                // Un serveur peut mêler texte et JSON ; seul le contrat structuré est exploité.
            }
        }
        return null;
    }
}
