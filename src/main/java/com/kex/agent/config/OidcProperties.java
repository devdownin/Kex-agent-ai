// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Lecture d'un jeton OIDC. Rien ici n'active quoi que ce soit : la chaîne JWT n'existe que si
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} (ou {@code jwk-set-uri}) est
 * renseignée. Ces propriétés disent seulement comment lire le jeton une fois qu'il arrive.
 *
 * @param usernameClaim claim qui porte l'acteur inscrit à l'audit. « approuvé par ops-console »
 *                      nomme une clé partagée, pas une personne : c'est exactement ce qu'un
 *                      registre de conformité ne doit pas dire. Repli sur {@code sub} — illisible,
 *                      mais unique et vrai.
 * @param rolesClaim    claim des rôles, liste de chaînes ou chaîne séparée par des espaces
 * @param roleMappings  correspondance valeur du claim → rôle de l'agent, par exemple
 *                      {@code kex-ops: OPERATOR}. Une valeur absente de cette table est essayée
 *                      telle quelle contre {@link ApiRole}, à la casse près.
 * @param tenantClaim   claim qui porte le locataire. Vide, tous les porteurs de jeton partagent
 *                      {@code defaultTenant} : un émetteur OIDC dessert une organisation, et
 *                      donner à chaque personne son propre espace ferait qu'une compétence
 *                      approuvée par l'un n'agirait jamais pour l'autre.
 */
@ConfigurationProperties("kex.agent.oidc")
public record OidcProperties(

        @DefaultValue("preferred_username") String usernameClaim,

        @DefaultValue("roles") String rolesClaim,

        @DefaultValue Map<String, ApiRole> roleMappings,

        String tenantClaim,

        @DefaultValue("default") String defaultTenant,

        /**
         * Rôle accordé à un jeton valide dont aucun rôle ne se traduit. {@code CHAT} par défaut,
         * comme une clé nommée absente de {@code api-key-roles} : l'émetteur a déjà décidé qui
         * obtient un jeton, ce plancher décide ce qu'il en fait. {@code null} pour n'accorder
         * aucun rôle — le jeton s'authentifie alors sans rien pouvoir faire.
         */
        @DefaultValue("CHAT") ApiRole defaultRole) {
}
