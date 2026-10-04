// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolResult;
import org.springframework.util.StringUtils;

/** Bounded, deterministic verification; it never invokes or replays the mutating tool. */
final class ActionVerification {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ActionVerification() {
    }

    static Decision verify(Decision decision, ActionBinding action, String actor, Instant actionStarted,
                           Clock clock, McpToolCatalog tools, String receipt) {
        VerificationBinding verifier = action.verification();
        if (verifier == null) {
            return outcome(decision, DecisionStatus.EXECUTED_UNVERIFIED, actor, clock, receipt,
                    "Objectif non vérifié : aucun vérificateur indépendant configuré");
        }
        try {
            if (!StringUtils.hasText(verifier.connection()) || !StringUtils.hasText(verifier.tool())
                    || (verifier.connection().equals(action.connection()) && verifier.tool().equals(action.tool()))
                    || !tools.isReadOnly(verifier.connection(), verifier.tool())
                    || !tools.isToolAllowed(verifier.connection(), verifier.tool())) {
                return unknown(decision, actor, clock, receipt,
                        "Vérificateur distinct en lecture seule non autorisé");
            }
            JsonPointer resultPointer = pointer(verifier.resultPointer());
            JsonPointer timestampPointer = pointer(verifier.observedAtPointer());
            JsonNode expected = JSON.valueToTree(verifier.expectedValue());
            if (expected == null || expected.isNull() || !expected.isValueNode()
                    || verifier.maxAge().isNegative() || verifier.maxAge().isZero()) {
                return unknown(decision, actor, clock, receipt, "Contrat de postcondition invalide");
            }
            Map<String, Object> arguments = new HashMap<>(verifier.arguments());
            arguments.put("processId", decision.processId());
            arguments.put("decisionId", decision.id());
            McpToolResult result = tools.call(verifier.connection(), verifier.tool(), arguments);
            if (result == null || result.error() || result.structuredContent() == null) {
                return unknown(decision, actor, clock, receipt,
                        "Vérificateur indisponible ou preuve structurée absente");
            }
            JsonNode evidence = JSON.valueToTree(result.structuredContent());
            if (!evidence.isObject()) {
                return unknown(decision, actor, clock, receipt, "Preuve structurée invalide");
            }
            if (StringUtils.hasText(verifier.measuredPointer())) {
                JsonNode measured = evidence.at(pointer(verifier.measuredPointer()));
                if (!measured.isBoolean() || !measured.booleanValue()) {
                    return unknown(decision, actor, clock, receipt, "Postcondition non mesurée");
                }
            }
            // An explicitly incomplete diagnostic cannot establish success even if a field matches.
            JsonNode coverage = evidence.path("coverage");
            if (!coverage.isMissingNode() && (!coverage.isObject()
                    || !coverage.path("complete").isBoolean() || !coverage.path("complete").booleanValue()
                    || !"EXHAUSTED".equals(coverage.path("stopReason").asText())
                    || (!coverage.path("topicsNotReached").isMissingNode()
                        && (!coverage.path("topicsNotReached").isArray()
                            || !coverage.path("topicsNotReached").isEmpty()))
                    || (!coverage.path("notReached").isMissingNode()
                        && (!coverage.path("notReached").isArray()
                            || !coverage.path("notReached").isEmpty())))) {
                return unknown(decision, actor, clock, receipt, "Couverture de la preuve incomplète");
            }
            JsonNode observedAt = evidence.at(timestampPointer);
            if (!observedAt.isTextual()) {
                return unknown(decision, actor, clock, receipt, "Date de mesure absente");
            }
            Instant measuredAt = Instant.parse(observedAt.textValue());
            Instant now = clock.instant();
            if (measuredAt.isBefore(actionStarted) || measuredAt.isBefore(now.minus(verifier.maxAge()))
                    || measuredAt.isAfter(now)) {
                return unknown(decision, actor, clock, receipt, "Preuve périmée ou date incohérente");
            }
            JsonNode actual = evidence.at(resultPointer);
            if (actual.isMissingNode() || actual.isNull() || !actual.isValueNode()) {
                return unknown(decision, actor, clock, receipt, "Valeur de postcondition absente");
            }
            boolean confirmed = actual.isNumber() && expected.isNumber()
                    ? actual.decimalValue().compareTo(expected.decimalValue()) == 0 : actual.equals(expected);
            String proof = "Vérification %s via %s/%s : %s = %s (attendu %s), mesuré à %s"
                    .formatted(confirmed ? "confirmée" : "échouée", verifier.connection(), verifier.tool(),
                            verifier.resultPointer(), bounded(actual.toString()), bounded(expected.toString()), measuredAt);
            return outcome(decision, confirmed ? DecisionStatus.VERIFIED : DecisionStatus.VERIFICATION_FAILED,
                    actor, clock, receipt, proof);
        }
        catch (RuntimeException ex) {
            // The action has already been accepted: verification errors must never mark it retryable.
            return unknown(decision, actor, clock, receipt,
                    "Vérification indéterminée : " + ex.getClass().getSimpleName());
        }
    }

    private static JsonPointer pointer(String value) {
        if (!StringUtils.hasText(value) || !value.startsWith("/")) {
            throw new IllegalArgumentException("Un pointeur JSON explicite est requis");
        }
        return JsonPointer.compile(value);
    }

    private static String bounded(String value) {
        return value.length() > 256 ? value.substring(0, 256) + "…" : value;
    }

    private static Decision unknown(Decision decision, String actor, Clock clock, String receipt, String reason) {
        return outcome(decision, DecisionStatus.VERIFICATION_UNKNOWN, actor, clock, receipt, reason);
    }

    private static Decision outcome(Decision decision, DecisionStatus status, String actor, Clock clock,
                                    String receipt, String proof) {
        return decision.resolvedAs(status, receipt + "\n" + proof, actor, clock.instant());
    }
}
