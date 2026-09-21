// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.security.Principal;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le locataire ne se substitue à l'acteur que lorsqu'on l'a déclaré. Toute la compatibilité de
 * l'axe locataire tient dans ce repli : sans lui, une installation existante ne retrouverait ni
 * ses souvenirs, ni ses compétences, ni sa charte — ils sont rangés sous le nom de la clé.
 */
class ActorIdentityTest {

    @Test
    void sans_locataire_declare_le_locataire_est_le_nom() {
        assertThat(new ActorIdentity("ops-console", null).tenant()).isEqualTo("ops-console");
        assertThat(new ActorIdentity("ops-console", "  ").tenant()).isEqualTo("ops-console");
    }

    @Test
    void deux_cles_du_meme_locataire_partagent_l_espace_sans_se_confondre() {
        ActorIdentity console = new ActorIdentity("ops-console", "exploitation");
        ActorIdentity pipeline = new ActorIdentity("ci-pipeline", "exploitation");

        assertThat(console.tenant()).isEqualTo(pipeline.tenant());
        assertThat(console.getName()).isNotEqualTo(pipeline.getName());
    }

    @Test
    void un_principal_nu_reste_son_propre_locataire() {
        Principal principal = () -> "admin";

        assertThat(ActorIdentity.tenantOf(principal)).isEqualTo("admin");
        assertThat(ActorIdentity.tenantOf(null)).isNull();
    }

    @Test
    void lit_le_locataire_du_jeton_d_authentification() {
        Principal authentication = UsernamePasswordAuthenticationToken.authenticated(
                new ActorIdentity("alice", "exploitation"), null, AuthorityUtils.NO_AUTHORITIES);

        assertThat(ActorIdentity.tenantOf(authentication)).isEqualTo("exploitation");
        assertThat(authentication.getName()).isEqualTo("alice");
    }

    @Test
    void refuse_un_acteur_sans_nom() {
        assertThatThrownBy(() -> new ActorIdentity("  ", "exploitation"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
