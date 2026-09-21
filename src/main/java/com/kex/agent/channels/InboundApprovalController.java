// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.util.Map;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.supervision.Decision;
import com.kex.agent.supervision.SupervisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

/**
 * Approuver ou refuser une demande de validation depuis la messagerie qui l'a annoncée. La
 * notification portait déjà un lien vers la console ; d'astreinte, à deux heures du matin, ce lien
 * suppose d'ouvrir un navigateur et de retrouver un jeton.
 *
 * <p>Cette route est la seule de l'API qui décide sans bearer : la messagerie n'en porte pas. Elle
 * s'en remet donc à trois verrous cumulés, dont aucun ne suffit seul — une signature HMAC sur le
 * corps brut avec fenêtre d'horodatage ({@link InboundSignature}), une correspondance explicite
 * entre l'expéditeur et un acteur d'audit nommé, et l'extinction par défaut. Un expéditeur non
 * déclaré est refusé : le rattacher à un acteur générique ferait dire à l'audit « approuvé par
 * slack », qui n'est pas une personne.
 *
 * <p>Elle n'ajoute aucun pouvoir : elle emprunte {@code approve} et {@code reject} avec leurs
 * verrous d'idempotence, leur expiration et leur audit. Une décision déjà tranchée y répond
 * {@code 409} comme partout ailleurs.
 */
@RestController
@RequestMapping("/api/agent/channels")
@ConditionalOnProperty(prefix = "kex.agent.channels.inbound", name = "enabled", havingValue = "true")
class InboundApprovalController {

    private static final Logger log = LoggerFactory.getLogger(InboundApprovalController.class);

    private final SupervisionService supervision;
    private final InboundSignature signature;
    private final ObjectMapper mapper;
    private final Map<String, String> operators;

    InboundApprovalController(SupervisionService supervision, InboundSignature signature,
                              ObjectMapper mapper, ChannelProperties properties) {
        this.supervision = supervision;
        this.signature = signature;
        this.mapper = mapper;
        this.operators = properties.inbound().operators();
    }

    @PostMapping("/callback")
    Decision callback(@RequestHeader(name = "X-Kex-Timestamp", required = false) String timestamp,
                      @RequestHeader(name = "X-Kex-Signature", required = false) String provided,
                      @RequestBody String body) {
        String refusal = signature.reject(timestamp, provided, body);
        if (refusal != null) {
            // Le détail part dans les journaux, pas dans la réponse : distinguer « signature
            // invalide » de « horodatage hors fenêtre » renseignerait qui tâtonne.
            log.warn("Rappel de canal refusé : {}", refusal);
            throw new InboundRefusedException();
        }
        Callback callback = parse(body);
        String actor = operators.get(callback.sender());
        if (actor == null) {
            log.warn("Rappel de canal signé, expéditeur non déclaré");
            throw new InboundRefusedException();
        }
        return callback.approve()
                ? supervision.approve(callback.decisionId(), actor)
                : supervision.reject(callback.decisionId(), callback.reason(), actor);
    }

    private Callback parse(String body) {
        Callback callback;
        try {
            callback = mapper.readValue(body, Callback.class);
        }
        catch (JacksonException ex) {
            throw new IllegalArgumentException("Corps de rappel illisible");
        }
        if (callback == null || isBlank(callback.sender()) || isBlank(callback.decisionId())) {
            throw new IllegalArgumentException("sender et decisionId requis");
        }
        return callback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @ExceptionHandler(InboundRefusedException.class)
    ProblemDetail refused(InboundRefusedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * @param sender identifiant de l'expéditeur chez le fournisseur de messagerie, tel que la
     *               configuration le déclare — jamais un nom d'affichage, qui se change
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Callback(String sender, String decisionId, boolean approve, String reason) { }

    static final class InboundRefusedException extends RuntimeException {
        InboundRefusedException() {
            super("Rappel refusé");
        }
    }
}
