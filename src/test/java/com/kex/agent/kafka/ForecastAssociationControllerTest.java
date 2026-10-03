// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@WebMvcTest(ForecastAssociationController.class)
@AutoConfigureMockMvc(addFilters = false)
class ForecastAssociationControllerTest {
    @Autowired MockMvcTester mvc;
    @MockitoBean ForecastAssociationService associations;
    @MockitoBean ForecastReadinessService readiness;

    @Test
    void validates_request_and_returns_persisted_choice() {
        var links = List.of(new ForecastAssociation("s1", "lab"));
        when(associations.replace("orders", links)).thenReturn(new ForecastAssociationService.Associations("orders", links, true, null));
        assertThat(mvc.put().uri("/api/agent/forecasts/processes/orders/associations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"associations\":[{\"seriesId\":\"s1\",\"environment\":\"lab\"}]}"))
                .hasStatusOk().bodyJson().extractingPath("$.overridden").isEqualTo(true);
        assertThat(mvc.put().uri("/api/agent/forecasts/processes/orders/associations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"associations\":[{\"seriesId\":\"\",\"environment\":\"lab\"}]}"))
                .hasStatus(400);
        verify(associations).replace("orders", links);
        verifyNoMoreInteractions(associations);
    }
}
