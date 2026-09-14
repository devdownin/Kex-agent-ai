package com.kex.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("kex.agent")
public record AgentProperties(

        @DefaultValue("""
                Tu es Kex, un agent IA outillé. Tu disposes d'outils exposés par des serveurs MCP.
                Utilise-les dès qu'ils permettent de répondre factuellement plutôt que de supposer.
                Réponds de façon concise et cite l'outil utilisé quand le résultat en provient.""")
        String systemPrompt,

        /** Fenêtre de contexte conservée par conversation (messages, pas tokens). */
        @DefaultValue("40") int maxHistoryMessages,

        /** Journalise prompts et réponses : à laisser à false hors debug (données sensibles). */
        @DefaultValue("false") boolean logInteractions,

        /**
         * Bearer exigé sur /api/**. Vide, l'API refuse tout avec 503 : un agent qui dépense des
         * jetons et exécute des outils MCP ne s'ouvre pas par défaut d'installation.
         */
        @DefaultValue("") String apiKey) {
}
