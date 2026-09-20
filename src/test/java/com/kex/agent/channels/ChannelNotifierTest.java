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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    @Test
    void payload_builders_build_valid_structures() {
        ChannelMessage msg = new ChannelMessage("subject text", "body text", URI.create("https://action.example"), Instant.parse("2026-09-20T13:00:00Z"));

        Map<String, Object> slackPayload = SlackChannelAdapter.payload(msg);
        assertThat(slackPayload).containsEntry("text", "Notification Kex");

        Map<String, Object> teamsPayload = TeamsChannelAdapter.payload(msg);
        assertThat(teamsPayload).containsEntry("type", "message");
    }
}
