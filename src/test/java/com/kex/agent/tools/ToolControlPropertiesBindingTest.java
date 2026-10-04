// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.tools;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolControlPropertiesBindingTest {

    @Test
    void les_json_pointers_et_les_listes_de_refus_se_lient_depuis_la_configuration_administrateur() {
        var source = new MapConfigurationPropertySource(Map.of(
                "kex.agent.tools.rules.export.allowed-arguments[/topic][0]", "orders.prod",
                "kex.agent.tools.rules.export.destinations[/callback].hosts[0]", "ops.example.org",
                "kex.agent.tools.denied-input-patterns[0]", "SECRET-[0-9]+",
                "kex.agent.tools.rules.read.read-only", "true"));
        ToolControlProperties properties = new Binder(source)
                .bind("kex.agent.tools", Bindable.of(ToolControlProperties.class)).get();
        assertThat(properties.rules().get("export").allowedArguments()).containsKey("/topic");
        assertThat(properties.rules().get("export").destinations()).containsKey("/callback");
        assertThat(properties.rules().get("read").readOnly()).isTrue();
        assertThat(properties.selectionEnabled()).isFalse();
        assertThatThrownBy(() -> new ToolInvocationPolicy(properties, new ObjectMapper())
                .check("export", Map.of("topic", "orders.secret", "callback", "https://ops.example.org")))
                .isInstanceOf(SecurityException.class);
    }
}
