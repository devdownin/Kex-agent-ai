// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import com.kex.agent.knowledge.KnowledgeProperties;
import com.kex.agent.supervision.ModelAvailability;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Lit la configuration du modèle dans l'{@code Environment} résolu.
 *
 * <p>Une seule implémentation pour la console et pour l'avertissement de démarrage : deux lectures
 * séparées finiraient par diverger, et un écran qui contredit les logs fait douter des deux.
 */
@Service
public class LlmViewService implements ModelAvailability {

    static final String ANTHROPIC = "anthropic";
    static final String OPENAI = "openai";

    private static final String OPENROUTER_HOST = "openrouter.ai";
    private static final String OPENAI_HOST = "api.openai.com";

    private final Environment environment;
    private final AgentProperties agent;
    private final KnowledgeProperties knowledge;

    LlmViewService(Environment environment, AgentProperties agent, KnowledgeProperties knowledge) {
        this.environment = environment;
        this.agent = agent;
        this.knowledge = knowledge;
    }

    public LlmView describe() {
        String provider = provider();
        String baseUrl = switch (provider) {
            case ANTHROPIC -> safeUrl(environment.getProperty("spring.ai.anthropic.base-url"));
            case OPENAI -> safeUrl(environment.getProperty("spring.ai.openai.base-url"));
            default -> null;
        };
        String variable = apiKeyVariable(provider);
        Boolean keyPresent = keyPresent(provider);
        boolean gateway = OPENAI.equals(provider) && !hostIs(baseUrl, OPENAI_HOST);

        List<String> warnings = new ArrayList<>();
        if (Boolean.FALSE.equals(keyPresent)) {
            warnings.add("Aucune clé pour le fournisseur retenu : chaque échange échouera. "
                    + "Définir " + variable + ", puis redémarrer l'agent.");
        }
        if (keyPresent == null) {
            warnings.add("Fournisseur « " + provider + " » : sa configuration n'est pas lue ici. "
                    + "Ce que cet écran n'affiche pas n'est pas absent, il n'a pas été regardé.");
        }
        if (gateway) {
            warnings.add("Passerelle hébergée : les prompts, l'historique de conversation et les "
                    + "résultats d'outils — donc le contenu des messages Kafka lus — quittent la "
                    + "machine et transitent par un tiers avant d'atteindre le modèle.");
        }
        if (agent.logInteractions()) {
            warnings.add("kex.agent.log-interactions est actif : prompts et réponses sont écrits "
                    + "dans les journaux. À réserver au débogage.");
        }

        return new LlmView(provider, label(provider, baseUrl), model(provider), baseUrl, gateway,
                keyPresent, variable, integer(provider, "max-tokens"), decimal(provider, "temperature"),
                environment.getProperty("spring.ai.tools.limits.max-total-tool-calls", Integer.class),
                environment.getProperty("spring.ai.tools.limits.on-limit-exceeded"),
                agent.requestTimeout().toString(), agent.maxHistoryMessages(), agent.logInteractions(),
                environment.getProperty("spring.ai.model.embedding", "none"), knowledge.enabled(),
                agent.systemPrompt(), List.copyOf(warnings));
    }

    /**
     * Lecture étroite pour la supervision, qui ne doit pas se déclarer opérationnelle quand aucun
     * échange ne peut aboutir. Recalculée à chaque appel : une clé posée par variable
     * d'environnement ne change pas à chaud, mais figer la réponse ferait mentir l'indicateur si
     * jamais elle le pouvait.
     */
    @Override
    public boolean keyKnownMissing() {
        return Boolean.FALSE.equals(keyPresent(provider()));
    }

    String provider() {
        return environment.getProperty("spring.ai.model.chat", ANTHROPIC);
    }

    /**
     * La variable d'environnement qui alimente la clé dans notre {@code application.yml}, pas celle
     * que le fournisseur documente : sur le fournisseur {@code openai} c'est {@code OPENROUTER_API_KEY}
     * qui est câblé, y compris pour une autre passerelle. Nommer autre chose enverrait poser une
     * variable que rien ne lit.
     */
    String apiKeyVariable(String provider) {
        return switch (provider) {
            case ANTHROPIC -> "ANTHROPIC_API_KEY";
            case OPENAI -> "OPENROUTER_API_KEY";
            default -> null;
        };
    }

    Boolean keyPresent(String provider) {
        return switch (provider) {
            case ANTHROPIC -> StringUtils.hasText(environment.getProperty("spring.ai.anthropic.api-key"));
            case OPENAI -> StringUtils.hasText(environment.getProperty("spring.ai.openai.api-key"));
            default -> null;
        };
    }

    private String model(String provider) {
        return switch (provider) {
            case ANTHROPIC -> environment.getProperty("spring.ai.anthropic.chat.options.model");
            case OPENAI -> environment.getProperty("spring.ai.openai.chat.options.model");
            default -> null;
        };
    }

    private Integer integer(String provider, String option) {
        return option(provider, option, Integer.class);
    }

    private Double decimal(String provider, String option) {
        return option(provider, option, Double.class);
    }

    private <T> T option(String provider, String option, Class<T> type) {
        return switch (provider) {
            case ANTHROPIC -> environment.getProperty("spring.ai.anthropic.chat.options." + option, type);
            case OPENAI -> environment.getProperty("spring.ai.openai.chat.options." + option, type);
            default -> null;
        };
    }

    private String label(String provider, String baseUrl) {
        return switch (provider) {
            case ANTHROPIC -> "Anthropic";
            case OPENAI -> {
                if (hostIs(baseUrl, OPENROUTER_HOST)) yield "OpenRouter";
                yield hostIs(baseUrl, OPENAI_HOST) ? "OpenAI" : "Passerelle compatible OpenAI";
            }
            default -> provider;
        };
    }

    /** Une base d'URL absente vaut le point d'accès du fournisseur : l'appel est direct. */
    private static boolean hostIs(String baseUrl, String host) {
        if (!StringUtils.hasText(baseUrl)) return OPENAI_HOST.equals(host);
        try {
            String actual = URI.create(baseUrl).getHost();
            return actual != null && (actual.equals(host) || actual.endsWith("." + host));
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * Une base d'URL peut porter des identifiants en {@code userinfo} — {@code https://jeton@hote/} —
     * et une chaîne de requête peut porter une clé. L'écran ne rend que schéma, hôte, port et
     * chemin ; ce qui ne se relit pas est dit illisible plutôt que recopié au hasard.
     */
    private static String safeUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) return null;
        try {
            URI uri = new URI(baseUrl);
            if (uri.getHost() == null) return "(illisible)";
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
                    .toString();
        }
        catch (URISyntaxException ex) {
            return "(illisible)";
        }
    }
}
