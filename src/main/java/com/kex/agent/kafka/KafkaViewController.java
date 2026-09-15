// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La vue technique du cluster. Aucun gestionnaire d'erreur ici : le service ne lève pas, il rend
 * une vue vide qui dit pourquoi. Un serveur MCP absent est une information d'exploitation, pas une
 * panne de l'agent, et la console doit pouvoir l'afficher plutôt que de tomber sur un code d'erreur.
 */
@RestController
@RequestMapping("/api/agent/kafka")
class KafkaViewController {

    private final KafkaViewService kafka;

    KafkaViewController(KafkaViewService kafka) {
        this.kafka = kafka;
    }

    @GetMapping("/topics")
    KafkaTopics topics() {
        return kafka.topics();
    }

    /** Le nom du topic est dans le chemin : c'est le vocabulaire de la documentation Kafka. */
    @GetMapping("/topics/{topic}/lag")
    KafkaTopicLag lag(@PathVariable String topic) {
        return kafka.lag(topic);
    }
}
