// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Vue technique Kafka, adossée aux outils de Kafka SQL Explorer.
 *
 * <p>Les noms d'outils sont configurables et non codés en dur : l'agent peut être branché sur un
 * autre serveur MCP, et une vue qui échoue en nommant l'outil attendu se règle, là où une vue qui
 * échoue en silence se contourne.
 *
 * @param connection clé de connexion MCP à interroger. Vide, la vue se déclare non configurée au
 *                   lieu d'afficher un écran vide qu'on lirait comme « aucun topic »
 */
@ConfigurationProperties("kex.agent.kafka")
@Validated
public record KafkaProperties(

        @DefaultValue("kafka-explorer") String connection,

        @DefaultValue("kex_list_topics") String topicsTool,

        @DefaultValue("kex_consumer_lag") String lagTool,

        /** Plafond passé à l'outil ; il applique le sien, plus bas, s'il en a un. */
        @DefaultValue("200") @Positive int maxTopics) {
}
