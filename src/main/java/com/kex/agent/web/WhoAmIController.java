// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import java.util.List;

import com.kex.agent.config.ActorIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ce que la console ne pouvait dire à personne jusqu'ici : à qui appartiennent les compétences,
 * la charte et la mémoire qu'elle affiche. L'acteur et le locataire divergent dès qu'un locataire
 * est déclaré (voir {@link ActorIdentity}), et deux clés du même opérateur peuvent porter des rôles
 * différents — rien de tout cela n'était visible sans lire l'audit ou la configuration du serveur.
 *
 * <p>Route de lecture pure, sans effet : ouverte à tout principal authentifié, {@code CHAT} compris,
 * comme les routes de conversation elles-mêmes — voir {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/agent/whoami")
class WhoAmIController {

    @GetMapping
    WhoAmI current(Authentication authentication) {
        if (authentication == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String tenant = ActorIdentity.tenantOf(authentication);
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
                .sorted()
                .toList();
        return new WhoAmI(authentication.getName(), tenant, roles);
    }

    /**
     * @param name   l'acteur inscrit à l'audit — une personne sous OIDC, un nom d'intégration
     *               avec une clé API
     * @param tenant l'espace de données que ce principal lit et modifie : mémoire, compétences,
     *               charte. Égal à {@code name} tant qu'aucun locataire n'est déclaré
     */
    record WhoAmI(String name, String tenant, List<String> roles) { }
}
