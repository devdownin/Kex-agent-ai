// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les deux formes d'authentification arrivent dans le même en-tête.
 * {@code BearerTokenAuthenticationFilter} n'examine pas si quelqu'un s'est authentifié avant lui :
 * sans ce résolveur, déclarer un émetteur OIDC aurait fait basculer en 401 toutes les clés API
 * d'une installation existante, le jour même où on ajoute l'OIDC « sans rien casser ».
 */
class ApiKeyAwareBearerTokenResolverTest {

    private final ApiKeyAwareBearerTokenResolver resolver = new ApiKeyAwareBearerTokenResolver(
            List.of("jeton-ops".getBytes(StandardCharsets.UTF_8),
                    "jeton-admin".getBytes(StandardCharsets.UTF_8)));

    @Test
    void une_cle_api_connue_reste_invisible_au_serveur_de_ressources() {
        assertThat(resolver.resolve(request("Bearer jeton-ops"))).isNull();
        assertThat(resolver.resolve(request("Bearer jeton-admin"))).isNull();
    }

    @Test
    void tout_autre_jeton_passe_au_serveur_de_ressources() {
        assertThat(resolver.resolve(request("Bearer eyJhbGciOi.charge.signature")))
                .isEqualTo("eyJhbGciOi.charge.signature");
    }

    /** Un préfixe de clé connue n'est pas la clé : sans quoi un jeton tronqué court-circuiterait. */
    @Test
    void ne_confond_pas_un_prefixe_avec_la_cle() {
        assertThat(resolver.resolve(request("Bearer jeton-op"))).isEqualTo("jeton-op");
    }

    @Test
    void sans_en_tete_il_n_y_a_rien_a_resoudre() {
        assertThat(resolver.resolve(new MockHttpServletRequest())).isNull();
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", authorization);
        return request;
    }
}
