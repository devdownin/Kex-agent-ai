// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Le catalogue de la passerelle, pour que {@code OPENROUTER_MODEL} ne se remplisse plus à
 * l'aveugle. Cet agent ne sert à rien sans appel d'outils, et tous les modèles offerts n'en sont
 * pas capables : c'est ce que cette vue existe pour dire.
 *
 * <p>Rien n'y lève d'exception. Une passerelle injoignable est une information d'exploitation, pas
 * une panne de l'agent : la vue est vide et dit pourquoi, comme la vue Kafka.
 */
@Service
class LlmCatalogService {

    private static final Logger log = LoggerFactory.getLogger(LlmCatalogService.class);

    /**
     * L'appel sort de la machine et coûte à un tiers ; le panneau, lui, se rouvre d'un clic. Le
     * catalogue d'une passerelle ne bouge pas d'une minute à l'autre.
     */
    private static final Duration FRESH_FOR = Duration.ofMinutes(5);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Un panneau qui tourne trente secondes est un panneau qu'on ferme : mieux vaut dire l'échec. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final Environment environment;
    private final LlmViewService llm;
    private final RestClient.Builder clients;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    private record Cached(Instant at, LlmModels models) {
    }

    LlmCatalogService(Environment environment, LlmViewService llm, RestClient.Builder clients) {
        this.environment = environment;
        this.llm = llm;
        this.clients = clients;
    }

    LlmModels models() {
        Cached cached = cache.get();
        if (cached != null && cached.at().isAfter(Instant.now().minus(FRESH_FOR))) {
            return cached.models();
        }
        LlmModels fresh = fetch();
        // Les échecs ne sont pas mis en cache : une passerelle qui revient doit être vue tout de
        // suite, et l'appel n'a rien coûté à personne puisqu'il n'a pas abouti.
        if (fresh.unavailable() == null) {
            cache.set(new Cached(Instant.now(), fresh));
        }
        return fresh;
    }

    private LlmModels fetch() {
        LlmView view = llm.describe();
        String selected = view.model();
        if (!LlmViewService.OPENAI.equals(view.provider())) {
            return LlmModels.unavailable(selected, "Le catalogue n'est lu que sur une passerelle "
                    + "compatible OpenAI. Le fournisseur retenu est « " + view.label() + " ».");
        }

        String base = environment.getProperty("spring.ai.openai.base-url",
                "https://api.openai.com");
        try {
            // La clé part vers le point d'accès déjà configuré pour recevoir chaque échange :
            // aucune confiance nouvelle n'est accordée ici. Certaines passerelles exigent le
            // bearer sur /models, OpenRouter non.
            RestClient client = clients.clone()
                    .requestFactory(requestFactory())
                    .baseUrl(base)
                    .defaultHeaders(headers -> {
                        String key = environment.getProperty("spring.ai.openai.api-key");
                        if (StringUtils.hasText(key)) headers.setBearerAuth(key);
                    })
                    .build();

            Map<?, ?> body = client.get().uri("/models").retrieve().body(Map.class);
            if (!(body instanceof Map<?, ?> envelope) || !(envelope.get("data") instanceof Collection<?> data)) {
                return LlmModels.unavailable(selected,
                        "Réponse illisible : aucune liste `data` dans ce que la passerelle a rendu.");
            }
            return new LlmModels(parse(data, selected), selected, null);
        }
        catch (RuntimeException ex) {
            // Le message porte ce qu'un exploitant peut corriger — hôte, code HTTP — pas la pile.
            log.warn("Catalogue de modèles illisible sur {} : {}", base, ex.getMessage());
            return LlmModels.unavailable(selected, "Passerelle injoignable ou refusée : " + ex.getMessage());
        }
    }

    private static List<LlmModel> parse(Collection<?> data, String selected) {
        return data.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(entry -> model(entry, selected))
                .filter(model -> StringUtils.hasText(model.id()))
                // Le modèle retenu en tête, le reste par identifiant : un ordre stable, qu'on ne
                // trie pas par capacité pour ne pas suggérer un classement qu'on n'a pas mesuré.
                .sorted(Comparator.comparing(LlmModel::selected).reversed()
                        .thenComparing(LlmModel::id))
                .toList();
    }

    private static LlmModel model(Map<?, ?> entry, String selected) {
        String id = text(entry.get("id"));
        return new LlmModel(id, text(entry.get("name")), integer(entry.get("context_length")),
                toolCalling(entry.get("supported_parameters")), id != null && id.equals(selected));
    }

    /**
     * {@code null} quand la passerelle ne publie pas ses paramètres : ne pas le dire n'est pas le
     * refuser, et cet agent écarterait sinon des modèles utilisables.
     */
    private static Boolean toolCalling(Object supported) {
        return supported instanceof Collection<?> parameters
                ? parameters.stream().anyMatch(parameter -> "tools".equals(text(parameter)))
                : null;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String text(Object value) {
        return value instanceof String string && StringUtils.hasText(string) ? string : null;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
