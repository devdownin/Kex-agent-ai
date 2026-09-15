// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Signale au démarrage un fournisseur de modèle retenu sans clé.
 *
 * <p>Un avertissement, pas un échec : l'agent doit démarrer pour que {@code /actuator/health}, la
 * console et l'introspection MCP restent joignables — c'est précisément là qu'on va regarder
 * pourquoi rien ne répond. Même posture que l'absence de {@code kex.agent.api-key}, qui se
 * signale au démarrage et se refuse à l'appel.
 *
 * <p>Le message nomme la variable d'environnement attendue plutôt que la propriété Spring : c'est
 * elle qu'on va poser dans un compose ou un shell, et une erreur qui ne nomme pas le geste à
 * faire se contourne au lieu de se corriger.
 */
@Component
class LlmProviderCheck {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderCheck.class);

    private final Environment environment;

    LlmProviderCheck(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        String provider = environment.getProperty("spring.ai.model.chat", "anthropic");
        switch (provider) {
            case "anthropic" -> warnIfBlank("spring.ai.anthropic.api-key", "ANTHROPIC_API_KEY", provider);
            case "openai" -> {
                warnIfBlank("spring.ai.openai.api-key", "OPENROUTER_API_KEY", provider);
                String base = environment.getProperty("spring.ai.openai.base-url", "");
                log.info("Modèle de conversation : {} via {} ({})", model(), base,
                        base.contains("openrouter.ai")
                                ? "les prompts et les résultats d'outils transitent par OpenRouter"
                                : "passerelle compatible OpenAI");
            }
            // Un fournisseur qu'on ne connaît pas n'est pas forcément une erreur : le classpath
            // peut en porter un autre. On ne prétend pas vérifier ce qu'on ne sait pas lire.
            default -> log.info("Fournisseur de modèle « {} » : configuration non vérifiée ici", provider);
        }
    }

    private String model() {
        return environment.getProperty("spring.ai.openai.chat.options.model", "non précisé");
    }

    private void warnIfBlank(String property, String variable, String provider) {
        if (!StringUtils.hasText(environment.getProperty(property))) {
            log.warn("spring.ai.model.chat={} mais {} est vide : chaque échange échouera. "
                    + "Définir {}.", provider, property, variable);
        }
    }
}
