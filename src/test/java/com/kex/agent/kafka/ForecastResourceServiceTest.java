// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;

import com.kex.agent.supervision.MonitoredProcess;
import com.kex.agent.supervision.SupervisionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ForecastResourceServiceTest {
    private final ForecastViewService forecasts = mock(ForecastViewService.class);
    private final SupervisionService supervision = mock(SupervisionService.class);
    private final ForecastResourceService resources = new ForecastResourceService(forecasts,
            new ForecastLinkProperties(List.of(
                    new ForecastLinkProperties.ProcessLink("s1", "prod", List.of("orders", "deleted")),
                    new ForecastLinkProperties.ProcessLink("s1", "dev", List.of("wrong-env")))), supervision);

    @Test
    void links_only_explicit_current_processes_in_the_exact_environment() {
        catalog("{\"definitionVersion\":\"v1\",\"complete\":true,\"topics\":[\"orders\"],\"groups\":[\"consumer\"]}", true);
        when(supervision.processes()).thenReturn(List.of(
                new MonitoredProcess("orders", "Commandes", "", "", null),
                new MonitoredProcess("wrong-env", "Autre environnement", "", "", null),
                new MonitoredProcess("guessed", "orders consumer", "", "", null)));
        var result = resources.resources("s1");
        assertThat(result.unavailable()).isNull();
        assertThat(result.topics()).containsExactly("orders");
        assertThat(result.groups()).containsExactly("consumer");
        assertThat(result.processes()).containsExactly(new ForecastResourceService.Process("orders", "Commandes"));
    }

    @Test
    void denied_unknown_incomplete_or_redacted_provenance_never_exposes_links() {
        when(forecasts.metrics()).thenReturn(ForecastViewService.Read.failed("denied"));
        assertThat(resources.resources("s1").topics()).isEmpty();
        for (String source : List.of("null", "{}",
                "{\"definitionVersion\":\"v1\",\"complete\":false,\"topics\":[\"orders\"],\"groups\":[]}",
                "{\"definitionVersion\":\"v1\",\"complete\":true,\"topics\":[5],\"groups\":[]}")) {
            catalog(source, true);
            assertThat(resources.resources("s1").unavailable()).isNotBlank();
            assertThat(resources.resources("other").processes()).isEmpty();
        }
        catalog("{\"definitionVersion\":\"v1\",\"complete\":true,\"topics\":[\"orders\"],\"groups\":[]}", false);
        assertThat(resources.resources("s1").unavailable()).contains("incomplet");
        verifyNoInteractions(supervision);
    }

    private void catalog(String sources, boolean complete) {
        var json = JsonMapper.builder().build();
        when(forecasts.metrics()).thenReturn(new ForecastViewService.Read(
                json.readTree("[{\"seriesId\":\"s1\",\"environment\":\"prod\",\"sources\":" + sources + "}]"),
                json.readTree("{\"complete\":" + complete + "}"), json.readTree("[]"), false, null));
    }
}
