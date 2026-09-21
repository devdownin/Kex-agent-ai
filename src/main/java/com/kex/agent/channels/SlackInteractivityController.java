// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.supervision.SupervisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

/**
 * Reçoit le clic d'un bouton Approuver/Refuser posé par {@link SlackChannelAdapter}. Une seconde
 * entrée sans bearer, comme {@link InboundApprovalController}, mais un format et une signature
 * propres à Slack — voir {@link SlackRequestSignature} pour ce qui les distingue de l'entrée
 * générique.
 *
 * <p>{@code user.id} tel que Slack le rapporte résout l'acteur via {@code channels.inbound.operators}
 * — la même liste que la route générique, parce que c'est la même question : qui a le droit de
 * décider sans bearer. Un identifiant Slack non déclaré est refusé, jamais rattaché à un acteur
 * générique.
 *
 * <p>Ce qui manque volontairement : republier le message via {@code response_url} pour remplacer
 * les boutons par une confirmation. Sans lui, l'opérateur voit son clic pris en compte dans la
 * console mais les boutons restent affichés dans Slack jusqu'à ce qu'il quitte le canal ou que le
 * message défile — un deuxième clic retombe sur {@code DecisionInProgressException} ou {@code
 * DecisionNotPendingException} comme n'importe quelle décision déjà tranchée, jamais sur une
 * double exécution.
 */
@RestController
@RequestMapping("/api/agent/channels/slack")
@ConditionalOnProperty(prefix = "kex.agent.channels", name = "slack-signing-secret")
class SlackInteractivityController {

    private static final Logger log = LoggerFactory.getLogger(SlackInteractivityController.class);

    private final SupervisionService supervision;
    private final SlackRequestSignature signature;
    private final ObjectMapper mapper;
    private final Map<String, String> operators;

    SlackInteractivityController(SupervisionService supervision, SlackRequestSignature signature,
                                 ObjectMapper mapper, ChannelProperties properties) {
        this.supervision = supervision;
        this.signature = signature;
        this.mapper = mapper;
        this.operators = properties.inbound().operators();
    }

    @PostMapping(value = "/interactivity", consumes = "application/x-www-form-urlencoded")
    void interactivity(@RequestHeader(name = "X-Slack-Request-Timestamp", required = false) String timestamp,
                       @RequestHeader(name = "X-Slack-Signature", required = false) String provided,
                       @RequestBody String rawBody) {
        String refusal = signature.reject(timestamp, provided, rawBody);
        if (refusal != null) {
            log.warn("Interactivité Slack refusée : {}", refusal);
            throw new InboundApprovalController.InboundRefusedException();
        }
        BlockActions payload = parse(rawBody);
        String actor = operators.get(payload.user().id());
        if (actor == null) {
            log.warn("Interactivité Slack signée, expéditeur non déclaré");
            throw new InboundApprovalController.InboundRefusedException();
        }
        SlackAction action = payload.actions().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Aucune action dans le clic"));
        if ("kex_reject".equals(action.actionId())) {
            supervision.reject(action.value(), "Refusée depuis Slack", actor);
        }
        else {
            supervision.approve(action.value(), actor);
        }
        log.info("Décision {} tranchée depuis Slack par {}", action.value(), actor);
    }

    /**
     * Le seul champ que Slack pose dans un corps {@code application/x-www-form-urlencoded} : le
     * reste de la requête est ce JSON, encodé comme valeur de ce champ unique.
     */
    private BlockActions parse(String rawBody) {
        String encoded = rawBody.strip();
        if (encoded.startsWith("payload=")) {
            encoded = encoded.substring("payload=".length());
        }
        String json = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        try {
            BlockActions payload = mapper.readValue(json, BlockActions.class);
            if (payload == null || payload.user() == null || payload.user().id() == null
                    || payload.actions() == null || payload.actions().isEmpty()) {
                throw new IllegalArgumentException("Charge utile Slack incomplète");
            }
            return payload;
        }
        catch (JacksonException ex) {
            throw new IllegalArgumentException("Charge utile Slack illisible");
        }
    }

    @ExceptionHandler(InboundApprovalController.InboundRefusedException.class)
    ProblemDetail refused(InboundApprovalController.InboundRefusedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BlockActions(SlackUser user, List<SlackAction> actions) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SlackUser(String id) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SlackAction(@JsonProperty("action_id") String actionId, String value) { }
}
