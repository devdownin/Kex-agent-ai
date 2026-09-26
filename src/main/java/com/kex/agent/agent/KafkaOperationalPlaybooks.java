// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.Locale;

/** Curated read-only procedures; user-created skills still require human approval. */
final class KafkaOperationalPlaybooks {
    private KafkaOperationalPlaybooks() { }

    static String forRequest(String request) {
        if (request == null || request.isBlank()) return "";
        String text = request.toLowerCase(Locale.ROOT);
        StringBuilder guidance = new StringBuilder();
        if (text.matches("(?s).*(audit.*topic|config.*topic|rétention.*topic|replication.*topic|réplication.*topic|topic.*audit|topic.*config).*")) {
            guidance.append("""
                    Revue de configuration Kafka : utiliser kex_topic_configuration pour chaque topic visé.
                    Lire les paramètres et les réplicas/ISR effectivement mesurés, puis rapprocher
                    rétention, réplication et nettoyage des exigences propres à l'environnement.
                    Un paramètre non mesuré n'est pas une valeur par défaut. Un topic sans groupe
                    consommateur n'est pas nécessairement orphelin. Citer les valeurs et limites.
                    """);
        }
        if (text.matches("(?s).*(lag|retard.*consomm|consumer.*slow|consommateur.*retard|rebalanc).*")) {
            guidance.append("""
                    Diagnostic du lag Kafka : utiliser kex_consumer_lag pour le détail des partitions,
                    kex_diagnose_consumer pour l'état, puis kex_consumer_lag_trend pour comparer
                    deux relevés espacés. Le premier relevé n'a pas de tendance ; une tendance
                    indisponible n'est pas stable. Distinguer débit produit, débit consommé et
                    variation du backlog ; ne pas attribuer de cause sans preuve supplémentaire.
                    """);
        }
        if (text.matches("(?s).*(dlq|dlt|dead.letter|lettre.*morte|rejet.*message).*")) {
            guidance.append("""
                    Revue DLQ Kafka : utiliser kex_dlq_diagnosis avec le topic source explicite,
                    puis kex_dlq_review pour rétention, réplicas, groupes et en-têtes observés.
                    L'échantillon ne représente pas toute la file ; zéro groupe engagé ne prouve
                    pas l'absence de supervision. Vérifier la politique de retry et de retraitement
                    dans la configuration applicative avant de conclure. Ne pas retraiter automatiquement.
                    """);
        }
        return guidance.isEmpty() ? "" : "Procédure opérationnelle Kafka, à appliquer si les outils sont disponibles ; "
                + "respecter les permissions et les limites de couverture MCP.\n" + guidance;
    }
}
