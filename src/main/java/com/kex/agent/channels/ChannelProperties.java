// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Destinations configured by the operator, never supplied by model output. */
@ConfigurationProperties("kex.agent.channels")
public record ChannelProperties(@DefaultValue("false") boolean enabled,
                                @DefaultValue("") String consoleUrl,
                                @DefaultValue("") String slackWebhookUrl,
                                @DefaultValue("") String teamsWebhookUrl,
                                @DefaultValue Email email,
                                @DefaultValue Inbound inbound) {
    public record Email(@DefaultValue("false") boolean enabled,
                        @DefaultValue("") String from,
                        @DefaultValue List<String> recipients) {
        public Email {
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }
    }

    /**
     * Retour d'astreinte : approuver ou refuser depuis la messagerie où la demande est arrivée,
     * plutôt que d'ouvrir la console. C'est la seule entrée capable de déclencher une action
     * autonome sans bearer, d'où trois verrous cumulés et l'extinction par défaut.
     *
     * @param secret     clé du HMAC-SHA256 sur le corps brut. Aucune valeur par défaut : sans elle,
     *                   la route refuse de démarrer plutôt que d'accepter n'importe quel appelant
     * @param tolerance  écart maximum entre l'horodatage signé et l'heure locale. Sans fenêtre, une
     *                   requête signée capturée reste rejouable indéfiniment
     * @param operators  correspondance identifiant externe → acteur d'audit. Rien n'est déduit du
     *                   message : un expéditeur inconnu est refusé, jamais rattaché à un acteur
     *                   générique — sans quoi l'audit dirait « approuvé par slack » et plus qui
     */
    public record Inbound(@DefaultValue("false") boolean enabled,
                          @DefaultValue("") String secret,
                          @DefaultValue("5m") java.time.Duration tolerance,
                          @DefaultValue java.util.Map<String, String> operators) {
        public Inbound {
            operators = operators == null ? java.util.Map.of() : java.util.Map.copyOf(operators);
        }
    }
}
