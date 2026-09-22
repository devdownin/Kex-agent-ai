// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Un indice d'affichage pour la console, jamais un aiguillage : {@code spring.ai.model.chat}
 * dit seul quel {@code ChatModel} le contexte construit. {@code ollama}/{@code vllm} désignent le
 * runtime local que le fournisseur {@code openai} appelle en réalité ({@code
 * application-ollama.yml}/{@code application-vllm.yml}), pour que la console rende « Ollama (API
 * locale) » plutôt qu'une « Passerelle compatible OpenAI » générique, et propose la bonne variable
 * de clé facultative. Séparé de {@link LlmRoutingProperties} : ce dernier gouverne le routage
 * multi-modèles ({@code kex.models.enabled}), une fonctionnalité distincte qui n'a pas besoin de
 * ce nom pour fonctionner.
 */
@ConfigurationProperties("kex.models")
public record LocalModelProperties(@DefaultValue("") String localProvider) {
}
