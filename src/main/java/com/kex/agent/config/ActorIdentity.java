// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.security.Principal;

import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;

/**
 * Qui a agi, et à qui appartient ce qu'il manipule : deux questions distinctes que le nom d'une
 * clé API confondait. {@code name} part à l'audit — c'est une personne sous OIDC, un nom
 * d'intégration avec une clé API. {@code tenant} désigne l'espace de données : mémoire long terme,
 * compétences approuvées, charte, automatisations et conversations.
 *
 * <p>Sans configuration, {@code tenant} vaut {@code name} : une installation existante retrouve
 * exactement ses données là où elles étaient. Les deux ne divergent que lorsqu'on déclare un
 * locataire — {@code kex.agent.api-key-tenants} pour une clé, {@code kex.agent.oidc.tenant-claim}
 * ou {@code default-tenant} pour un jeton.
 *
 * <p>{@link AuthenticatedPrincipal} plutôt qu'une simple chaîne pour que
 * {@code Authentication.getName()} continue de rendre le nom : tout ce qui audite aujourd'hui
 * n'a rien à changer.
 */
public record ActorIdentity(String name, String tenant) implements AuthenticatedPrincipal, Principal {

    public ActorIdentity {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Un acteur authentifié porte toujours un nom");
        }
        tenant = tenant == null || tenant.isBlank() ? name : tenant;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * Le locataire de l'appelant. Le repli sur le nom n'est pas un cas dégradé : c'est le
     * comportement de toute installation qui n'a déclaré aucun locataire, et celui des tests qui
     * passent un {@code Principal} nu.
     */
    public static String tenantOf(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof ActorIdentity identity) {
            return identity.tenant();
        }
        if (principal instanceof ActorIdentity identity) {
            return identity.tenant();
        }
        return principal == null ? null : principal.getName();
    }
}
