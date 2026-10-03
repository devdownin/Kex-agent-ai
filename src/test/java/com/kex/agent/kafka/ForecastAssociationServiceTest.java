// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;
import java.util.Map;

import com.kex.agent.supervision.MonitoredProcess;
import com.kex.agent.supervision.SupervisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ForecastAssociationServiceTest {
    private final ForecastAssociationRepository repository = mock(ForecastAssociationRepository.class);
    private final ForecastViewService forecasts = mock(ForecastViewService.class);
    private final SupervisionService supervision = mock(SupervisionService.class);
    private final ForecastAssociation link = new ForecastAssociation("s1", "lab");
    private final ForecastAssociationService service = new ForecastAssociationService(repository,
            new ForecastLinkProperties(List.of(new ForecastLinkProperties.ProcessLink("s1", "lab", List.of("orders")))), forecasts, supervision);

    @BeforeEach
    void setUp() {
        when(supervision.processes()).thenReturn(List.of(new MonitoredProcess("orders", "Commandes", "", "", null)));
        when(repository.all()).thenReturn(Map.of());
        when(forecasts.metrics()).thenReturn(catalog(true));
    }

    @Test
    void validates_exact_pair_and_existing_process_before_writing() {
        assertThat(service.replace("orders", List.of(link)).associations()).containsExactly(link);
        verify(repository).replace("orders", List.of(link));
        assertThatThrownBy(() -> service.replace("unknown", List.of(link))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.replace("orders", List.of(new ForecastAssociation("s1", "other")))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.replace("orders", List.of(link, link))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.replace("orders", List.of(new ForecastAssociation("s1\n", "lab")))).isInstanceOf(ResponseStatusException.class);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void incomplete_catalogue_blocks_write_and_read_but_allows_explicit_clear() {
        when(forecasts.metrics()).thenReturn(catalog(false));
        assertThat(service.read("orders").associations()).isEmpty();
        assertThat(service.read("orders").unavailable()).contains("incomplet");
        assertThatThrownBy(() -> service.replace("orders", List.of(link))).isInstanceOf(ResponseStatusException.class);
        service.replace("orders", List.of());
        verify(repository).replace("orders", List.of());
    }

    @Test
    void persisted_override_replaces_config_only_for_selected_process_and_denied_ids_are_hidden() {
        when(repository.all()).thenReturn(Map.of("orders", List.of()));
        assertThat(service.processIds("s1", "lab")).isEmpty();
        assertThat(service.read("orders").overridden()).isTrue();
        when(repository.all()).thenReturn(Map.of("orders", List.of(new ForecastAssociation("secret", "other"))));
        assertThat(service.read("orders").associations()).isEmpty();
        assertThat(service.read("orders").unavailable()).contains("accessibles");
    }

    @Test
    void summary_reads_the_exact_environment_and_returns_unknowns_without_zero_substitution() {
        var failure = ForecastViewService.Read.failed("Non mesurée");
        when(forecasts.detail("s1", "lab")).thenReturn(new ForecastViewService.Detail("s1", failure, failure, failure));
        when(forecasts.breaches()).thenReturn(failure);
        var summary = service.summary("orders");
        assertThat(summary.forecasts()).hasSize(1);
        assertThat(summary.forecasts().getFirst().detail().quality().unavailable()).isEqualTo("Non mesurée");
        verify(forecasts).detail("s1", "lab");
        when(forecasts.metrics()).thenReturn(ForecastViewService.Read.failed("Denied"));
        assertThat(service.summary("orders").forecasts()).isEmpty();
        assertThat(service.summary("orders").unavailable()).isNotNull();
    }

    private ForecastViewService.Read catalog(boolean complete) {
        var json = JsonMapper.builder().build();
        return new ForecastViewService.Read(json.readTree("[{\"seriesId\":\"s1\",\"environment\":\"lab\"}]"),
                json.readTree("{\"complete\":" + complete + "}"), json.readTree("[]"), false, null);
    }
}
