// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que l'audit inscrira. « Approuvé par ops-console » nomme une clé partagée : c'est exactement
 * ce qu'un registre de conformité ne doit pas dire, et c'est ce qu'un jeton corrige.
 */
class JwtActorConverterTest {

    @Test
    void l_acteur_est_la_personne_nommee_par_le_claim() {
        AbstractAuthenticationToken authentication = convert(properties(null),
                Map.of("preferred_username", "alice@exemple.fr", "roles", List.of("kex-ops")));

        assertThat(authentication.getName()).isEqualTo("alice@exemple.fr");
        assertThat(authentication.getPrincipal()).isEqualTo(new ActorIdentity("alice@exemple.fr", "default"));
    }

    /** Illisible, mais unique et vrai : mieux qu'un acteur inventé pour une pièce de conformité. */
    @Test
    void retombe_sur_le_sujet_quand_le_claim_de_nom_manque() {
        assertThat(convert(properties(null), Map.of("roles", List.of("kex-ops"))).getName())
                .isEqualTo("sujet-42");
    }

    @Test
    void traduit_les_roles_par_la_table_declaree() {
        assertThat(authorities(convert(properties(null),
                Map.of("preferred_username", "alice", "roles", List.of("kex-ops")))))
                .containsExactly("ROLE_OPERATOR");
    }

    /** Un claim de rôles se présente aussi bien en liste qu'en chaîne séparée par des espaces. */
    @Test
    void accepte_un_claim_de_roles_en_chaine() {
        assertThat(authorities(convert(properties(null),
                Map.of("preferred_username", "alice", "roles", "kex-admins kex-ops"))))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_OPERATOR");
    }

    /**
     * Le jeton n'est pas cru sur parole : l'émetteur décide qui entre, l'exploitant décide ce
     * qu'on y fait. Un claim qui s'annonce {@code ADMIN} sans correspondance déclarée ne l'obtient
     * pas — sinon n'importe quel émetteur pourrait s'attribuer la politique de supervision.
     */
    @Test
    void un_role_non_traduit_retombe_sur_le_plancher() {
        OidcProperties properties = new OidcProperties("preferred_username", "roles",
                Map.of("kex-ops", ApiRole.OPERATOR), null, "default", ApiRole.CHAT);

        assertThat(authorities(convert(properties,
                Map.of("preferred_username", "mallory", "roles", List.of("groupe-inconnu")))))
                .containsExactly("ROLE_CHAT");
    }

    @Test
    void lit_le_locataire_dans_le_claim_quand_il_est_declare() {
        assertThat(convert(properties("org"), Map.of("preferred_username", "alice", "org", "exploitation"))
                .getPrincipal())
                .isEqualTo(new ActorIdentity("alice", "exploitation"));
    }

    /**
     * Sans claim de locataire, tous les porteurs partagent le même espace : un émetteur dessert
     * une organisation, et donner à chacun le sien ferait qu'une compétence approuvée par l'un
     * n'agirait jamais pour l'autre.
     */
    @Test
    void sans_claim_de_locataire_tous_partagent_le_meme_espace() {
        assertThat(ActorIdentity.tenantOf(convert(properties(null), Map.of("preferred_username", "alice"))))
                .isEqualTo(ActorIdentity.tenantOf(convert(properties(null), Map.of("preferred_username", "bob"))));
    }

    private static OidcProperties properties(String tenantClaim) {
        return new OidcProperties("preferred_username", "roles",
                Map.of("kex-ops", ApiRole.OPERATOR, "kex-admins", ApiRole.ADMIN),
                tenantClaim, "default", ApiRole.CHAT);
    }

    private static AbstractAuthenticationToken convert(OidcProperties properties, Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("jeton")
                .header("alg", "RS256")
                .subject("sujet-42")
                .issuedAt(Instant.parse("2026-09-21T10:00:00Z"))
                .expiresAt(Instant.parse("2026-09-21T11:00:00Z"))
                .claims(all -> all.putAll(claims))
                .build();
        return new JwtActorConverter(properties).convert(jwt);
    }

    private static List<String> authorities(AbstractAuthenticationToken authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}
