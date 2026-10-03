// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@WebMvcTest(ForecastResourceController.class)
@AutoConfigureMockMvc(addFilters = false)
class ForecastResourceControllerTest {
    @Autowired MockMvcTester mvc;
    @MockitoBean ForecastResourceService resources;

    @Test
    void resource_links_are_read_only_and_preserve_provenance() {
        given(resources.resources("s1")).willReturn(new ForecastResourceService.Resources(
                "s1", "prod", "v1", List.of("orders"), List.of("consumer"), List.of(), null));
        assertThat(mvc.get().uri("/api/agent/forecasts/series/s1/resources")).hasStatusOk()
                .bodyJson().extractingPath("$.topics[0]").isEqualTo("orders");
        assertThat(mvc.post().uri("/api/agent/forecasts/series/s1/resources")).hasStatus(405);
    }
}
