// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kex.agent.supervision.Decision;
import com.kex.agent.supervision.DecisionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChannelNotifierTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneId.of("UTC"));

    @Test
    void validates_console_url() {
        assertThat(ChannelNotifier.validateConsole("https://console.example.com"))
                .isEqualTo(URI.create("https://console.example.com"));

        assertThatThrownBy(() -> ChannelNotifier.validateConsole("http://insecure.example.com"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ChannelNotifier.validateConsole("https://user:pass@console.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sends_notification_to_all_adapters() {
        ChannelAdapter adapter1 = mock(ChannelAdapter.class);
        ChannelAdapter adapter2 = mock(ChannelAdapter.class);
        ChannelNotifier notifier = new ChannelNotifier(List.of(adapter1, adapter2), "https://console.example.com", clock);

        Optional<String> result = notifier.send("Subject", "Body");

        assertThat(result).isEmpty();
        verify(adapter1).send(any());
        verify(adapter2).send(any());
    }

    @Test
    void returns_error_when_no_adapters_configured() {
        ChannelNotifier notifier = new ChannelNotifier(List.of(), "https://console.example.com", clock);
        assertThat(notifier.send("Subject", "Body")).contains("Aucun canal configuré");
    }

    @Test
    void reports_failures_per_adapter() {
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        doThrow(new RuntimeException("network error")).when(adapter).send(any());
        org.mockito.BDDMockito.given(adapter.name()).willReturn("slack");

        ChannelNotifier notifier = new ChannelNotifier(List.of(adapter), "https://console.example.com", clock);
        assertThat(notifier.send("Subject", "Body")).contains("Échec de livraison : slack");
    }

    @Test
    void handles_approval_decision() {
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        ChannelNotifier notifier = new ChannelNotifier(List.of(adapter), "https://console.example.com", clock);

        Decision decision = new Decision("dec-1", "cycle-1", "anom-1", "proc-1", "Process Name",
                com.kex.agent.supervision.Capability.NOTIFY, "obj", "context", "restart",
                List.of(), "low", 0.9, DecisionStatus.PENDING_APPROVAL, "", "v1", "corr-1",
                null, clock.instant(), null, clock.instant().plusSeconds(3600));

        assertThat(notifier.approval(decision)).isEmpty();
        verify(adapter).send(any());

        // Expired decision
        Decision expired = new Decision("dec-2", "cycle-1", "anom-1", "proc-1", "Process Name",
                com.kex.agent.supervision.Capability.NOTIFY, "obj", "context", "restart",
                List.of(), "low", 0.9, DecisionStatus.PENDING_APPROVAL, "", "v1", "corr-1",
                null, clock.instant(), null, clock.instant().minusSeconds(10));
        assertThat(notifier.approval(expired)).contains("Demande absente ou expirée");
    }

    /**
     * {@code payload_builders_build_valid_structures} ne teste que la construction du corps —
     * jamais l'envoi HTTP réel, ni le traitement d'une réponse non-2xx, alors que c'est là que
     * {@link ChannelNotifier#deliver} traduit un échec d'adaptateur en « Échec de livraison ».
     * {@code MockRestServiceServer} pose une fausse réponse au niveau du {@link RestClient}
     * sous-jacent, plutôt que de simuler l'adaptateur lui-même.
     */
    @Test
    void slack_adapter_poste_reellement_sur_le_webhook() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://hooks.example.com/services/test"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        SlackChannelAdapter adapter = new SlackChannelAdapter(builder.build(),
                "https://hooks.example.com/services/test", false);
        adapter.send(new ChannelMessage("Subject", "Body", null, null, null));

        server.verify();
    }

    @Test
    void teams_adapter_poste_reellement_sur_le_webhook() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://outlook.example.com/webhook"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        TeamsChannelAdapter adapter = new TeamsChannelAdapter(builder.build(), "https://outlook.example.com/webhook");
        adapter.send(new ChannelMessage("Subject", "Body", null, null, null));

        server.verify();
    }

    /** Une réponse non-2xx doit remonter, pour que {@link ChannelNotifier#deliver} la compte en échec. */
    @Test
    void un_adaptateur_propage_une_reponse_non_2xx() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://hooks.example.com/services/test"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("invalid_payload"));

        SlackChannelAdapter adapter = new SlackChannelAdapter(builder.build(),
                "https://hooks.example.com/services/test", false);

        assertThatThrownBy(() -> adapter.send(new ChannelMessage("Subject", "Body", null, null, null)))
                .isInstanceOf(RestClientResponseException.class);
    }

    /**
     * Ferme la boucle avec un adaptateur réel plutôt qu'un mock : {@link ChannelNotifier} doit
     * traduire l'exception HTTP de {@link SlackChannelAdapter} en échec de livraison nommé, comme
     * il le fait déjà pour une {@link RuntimeException} arbitraire ({@code reports_failures_per_adapter}).
     */
    @Test
    void traduit_l_echec_d_un_adaptateur_reel_en_echec_de_livraison() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://hooks.example.com/services/test"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        SlackChannelAdapter adapter = new SlackChannelAdapter(builder.build(),
                "https://hooks.example.com/services/test", false);
        ChannelNotifier notifier = new ChannelNotifier(List.of(adapter), "https://console.example.com", clock);

        assertThat(notifier.send("Subject", "Body")).contains("Échec de livraison : slack");
        server.verify();
    }

    @Test
    void payload_builders_build_valid_structures() {
        ChannelMessage msg = new ChannelMessage("subject text", "body text", URI.create("https://action.example"), Instant.parse("2026-09-20T13:00:00Z"), null);

        Map<String, Object> slackPayload = SlackChannelAdapter.payload(msg, false);
        assertThat(slackPayload).containsEntry("text", "Notification Kex");

        Map<String, Object> teamsPayload = TeamsChannelAdapter.payload(msg);
        assertThat(teamsPayload).containsEntry("type", "message");
    }
}
