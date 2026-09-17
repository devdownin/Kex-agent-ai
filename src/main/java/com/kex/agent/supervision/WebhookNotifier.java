// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Seule sortie du processus que l'agent connaisse sans configuration MCP : {@link Capability#NOTIFY}
 * est la seule capacité dont le système cible est une personne, pas un système qui exigerait un
 * outil taillé pour son schéma — créer un incident ou redémarrer un consumer, eux, n'ont pas de
 * forme générique possible. Un webhook JSON générique (Slack, Teams, ou tout collecteur qui en
 * accepte un) couvre donc ce seul cas, à l'inverse du reste qui reste lié par {@link ActionBinding}.
 *
 * <p>Ne lève jamais : un webhook indisponible est un fait à rendre dans le résultat de la décision,
 * pas une panne du cycle de supervision qui l'a déclenché.
 */
@Service
class WebhookNotifier {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final NotifyProperties properties;

    WebhookNotifier(RestClient.Builder builder, NotifyProperties properties) {
        this.restClient = builder.requestFactory(requestFactory()).build();
        this.properties = properties;
    }

    /** @return vide en cas de succès, le motif d'échec sinon — jamais une exception */
    Optional<String> send(String subject, String body) {
        if (!StringUtils.hasText(properties.webhookUrl())) {
            return Optional.of("Aucun webhook de notification configuré (kex.agent.supervision.notify.webhook-url)");
        }
        try {
            restClient.post()
                    .uri(properties.webhookUrl())
                    .headers(headers -> {
                        if (StringUtils.hasText(properties.webhookToken())) {
                            headers.setBearerAuth(properties.webhookToken());
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", subject + "\n" + body))
                    .retrieve()
                    .toBodilessEntity();
            return Optional.empty();
        }
        catch (RestClientException ex) {
            return Optional.of("Webhook de notification injoignable : " + ex.getMessage());
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
