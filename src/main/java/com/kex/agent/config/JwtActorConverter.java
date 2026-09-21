// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

/**
 * Traduit un jeton validé en {@link ActorIdentity} : une personne nommée et son locataire, là où
 * une clé API ne rendait qu'un nom d'intégration.
 *
 * <p>Le jeton n'est jamais cru sur parole quant aux rôles : {@link OidcProperties#roleMappings}
 * dit quelles valeurs comptent. Un claim qui annoncerait {@code ADMIN} sans correspondance
 * déclarée retombe sur {@link OidcProperties#defaultRole} — l'émetteur décide qui entre,
 * l'exploitant décide ce qu'on y fait.
 */
final class JwtActorConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final OidcProperties properties;

    JwtActorConverter(OidcProperties properties) {
        this.properties = properties;
    }

    /**
     * Le même type de jeton d'authentification que pour une clé API, avec le même principal :
     * tout ce qui lit l'appelant en aval — audit, locataire, limitation de débit — n'a qu'une
     * forme à connaître. Les identifiants sont laissés à {@code null} : le jeton brut n'est
     * utile à personne en aval, et ne pas le porter c'est ne pas pouvoir le journaliser.
     */
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new ActorIdentity(name(jwt), tenant(jwt)), null, authorities(jwt));
    }

    private String name(Jwt jwt) {
        String claimed = claimAsText(jwt, properties.usernameClaim());
        // `sub` est illisible mais toujours présent et toujours vrai : mieux qu'un acteur inventé.
        return StringUtils.hasText(claimed) ? claimed : jwt.getSubject();
    }

    private String tenant(Jwt jwt) {
        if (StringUtils.hasText(properties.tenantClaim())) {
            String claimed = claimAsText(jwt, properties.tenantClaim());
            if (StringUtils.hasText(claimed)) {
                return claimed;
            }
        }
        return properties.defaultTenant();
    }

    private Collection<GrantedAuthority> authorities(Jwt jwt) {
        Set<ApiRole> roles = new LinkedHashSet<>();
        for (String claimed : claimAsList(jwt, properties.rolesClaim())) {
            ApiRole mapped = properties.roleMappings().get(claimed);
            roles.add(mapped != null ? mapped : parse(claimed));
        }
        roles.remove(null);
        if (roles.isEmpty() && properties.defaultRole() != null) {
            roles.add(properties.defaultRole());
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
    }

    private static ApiRole parse(String claimed) {
        try {
            return ApiRole.valueOf(claimed.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException unmapped) {
            return null;
        }
    }

    private static String claimAsText(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        return value instanceof String text ? text : null;
    }

    /** Un claim de rôles se présente aussi bien en liste qu'en chaîne séparée par des espaces. */
    private static List<String> claimAsList(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        if (value instanceof Collection<?> values) {
            return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        if (value instanceof String text) {
            return List.of(StringUtils.tokenizeToStringArray(text, " "));
        }
        return List.of();
    }
}
