// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@WebMvcTest(ForecastViewController.class)
@AutoConfigureMockMvc(addFilters = false)
class ForecastViewControllerTest {
    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    ForecastViewService forecasts;

    @Test
    void conserve_les_valeurs_json_du_contrat_http() throws Exception {
        var json = JsonMapper.builder().build();
        var read = new ForecastViewService.Read(json.readTree("[{\"seriesId\":\"s1\"}]"),
                json.readTree("{\"complete\":true}"), json.readTree("[]"), false, null);
        given(forecasts.metrics()).willReturn(read);
        assertThat(mvc.get().uri("/api/agent/forecasts/metrics")).hasStatusOk()
                .bodyJson().extractingPath("$.data[0].seriesId").isEqualTo("s1");
    }

    @Test
    void exposition_des_trois_lectures_sans_mutation() {
        var unavailable = ForecastViewService.Read.failed("Pas de serveur MCP");
        given(forecasts.metrics()).willReturn(unavailable);
        given(forecasts.breaches()).willReturn(unavailable);
        given(forecasts.detail("s1")).willReturn(new ForecastViewService.Detail("s1", unavailable, unavailable, unavailable));
        for (String path : new String[] {"metrics", "breaches"}) {
            assertThat(mvc.get().uri("/api/agent/forecasts/" + path)).hasStatusOk()
                    .bodyJson().extractingPath("$.unavailable").isEqualTo("Pas de serveur MCP");
        }
        assertThat(mvc.get().uri("/api/agent/forecasts/series/s1")).hasStatusOk()
                .bodyJson().extractingPath("$.forecast.unavailable").isEqualTo("Pas de serveur MCP");
        assertThat(mvc.post().uri("/api/agent/forecasts/metrics")).hasStatus(405);
    }
}
