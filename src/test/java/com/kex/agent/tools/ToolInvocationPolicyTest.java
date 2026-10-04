// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolInvocationPolicyTest {

    private ToolInvocationPolicy policy() {
        var rule = new ToolControlProperties.Rule(false, false,
                Map.of("/topic", Set.of("orders.prod")),
                Map.of("/callback", new ToolControlProperties.Destination(Set.of("ops.example.org"), Set.of("https"))));
        return new ToolInvocationPolicy(new ToolControlProperties(false, 3, 256, 1000,
                Map.of("export", rule, "blocked", new ToolControlProperties.Rule(true, false, Map.of(), Map.of()),
                        "read", new ToolControlProperties.Rule(false, true, Map.of(), Map.of())),
                List.of("SECRET-[0-9]+")), new ObjectMapper());
    }

    @Test
    void refuse_ressource_destination_identifiants_et_champs_absents_sans_exposer_les_valeurs() {
        ToolInvocationPolicy policy = policy();
        assertThatCode(() -> policy.check("server__export",
                "{\"topic\":\"orders.prod\",\"callback\":\"https://ops.example.org/hook\"}"))
                .doesNotThrowAnyException();
        for (String input : List.of(
                "{\"topic\":\"orders.secret\",\"callback\":\"https://ops.example.org/hook\"}",
                "{\"topic\":\"orders.prod\",\"callback\":\"https://ops.example.org.evil/hook\"}",
                "{\"topic\":\"orders.prod\",\"callback\":\"https://secret@ops.example.org/hook\"}",
                "{\"topic\":\"orders.prod\",\"callback\":\"http://ops.example.org/hook\"}",
                "{\"topic\":\"orders.prod\",\"callback\":\"file:///etc/passwd\"}",
                "{\"topic\":\"orders.prod\",\"callback\":\"https://127.0.0.1/hook\"}", "{}")) {
            assertThatThrownBy(() -> policy.check("export", input)).isInstanceOf(SecurityException.class)
                    .hasMessageNotContaining("orders.secret").hasMessageNotContaining("secret@").hasMessageNotContaining("passwd");
        }
    }

    @Test
    void refuse_lexfiltration_dun_secret_dans_les_valeurs_decodees_avant_invocation() {
        var policy = new ToolInvocationPolicy(new ToolControlProperties(false, 3, 256, 1000, Map.of(),
                List.of(), List.of("SECRET-[0-9]+")), new ObjectMapper());
        for (String input : List.of("{\"payload\":\"SECRET-42\"}",
                "{\"payload\":{\"values\":[\"SECRET-42\"]}}",
                "{\"SECRET-42\":\"payload\"}",
                "{\"payload\":\"SECR" + "\\u0045T-42\"}")) {
            assertThatThrownBy(() -> policy.check("send", input)).isInstanceOf(SecurityException.class)
                    .hasMessageNotContaining("SECRET-42");
        }
    }

    @Test
    void refuse_json_ambigu_ou_invalide_et_arguments_trop_grands() {
        for (String input : List.of("[]", "null", "{", "{} {}", "{\"topic\":\"a\",\"topic\":\"b\"}", " ".repeat(1001))) {
            assertThatThrownBy(() -> policy().check("read", input)).isInstanceOf(SecurityException.class);
        }
        assertThatThrownBy(() -> policy().check("blocked", "{}")).isInstanceOf(SecurityException.class);
        assertThat(policy().isDenied("server__blocked")).isTrue();
        assertThat(policy().isReadOnly("server__read")).isTrue();
        assertThat(policy().isReadOnly("unclassified")).isFalse();
    }

    @Test
    void une_regle_qualifiee_prend_priorite_et_les_arguments_restent_intacts() {
        var policy = new ToolInvocationPolicy(new ToolControlProperties(false, 3, 256, 1000,
                Map.of("export", new ToolControlProperties.Rule(true, false, Map.of(), Map.of()),
                        "trusted__export", new ToolControlProperties.Rule(false, true, Map.of(), Map.of())),
                List.of()), new ObjectMapper());
        var arguments = Map.<String, Object>of("topic", "orders.prod");
        assertThatCode(() -> policy.check("trusted__export", arguments)).doesNotThrowAnyException();
        assertThat(arguments).containsExactly(Map.entry("topic", "orders.prod"));
        assertThatThrownBy(() -> policy.check("other__export", arguments)).isInstanceOf(SecurityException.class);
    }

    @Test
    void refuse_un_secret_meme_apres_la_partie_qui_serait_tronquee() {
        assertThatThrownBy(() -> policy().boundResult("read", "x".repeat(500) + "SECRET-42"))
                .isInstanceOf(SecurityException.class).hasMessageNotContaining("SECRET-42");
        assertThat(policy().boundResult("read", "x".repeat(500))).hasSizeLessThanOrEqualTo(256)
                .contains("TRUNCATED", "originalCharacters=500", "sha256=");
        assertThat(policy().boundResult("read", null)).isEmpty();
    }
}
