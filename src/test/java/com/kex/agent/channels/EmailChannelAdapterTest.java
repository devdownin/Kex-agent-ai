// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EmailChannelAdapterTest {

    @Test
    void sends_email_with_configured_properties() {
        JavaMailSender sender = mock(JavaMailSender.class);
        ChannelProperties.Email properties = new ChannelProperties.Email(true, "ops@example.com", List.of("admin@example.com"));

        EmailChannelAdapter adapter = new EmailChannelAdapter(sender, properties);
        assertThat(adapter.name()).isEqualTo("email");

        ChannelMessage message = new ChannelMessage("Alert", "Body details", URI.create("https://action.example"), Instant.parse("2026-09-20T14:00:00Z"));
        adapter.send(message);

        var captor = forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());

        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getFrom()).isEqualTo("ops@example.com");
        assertThat(sent.getTo()).containsExactly("admin@example.com");
        assertThat(sent.getSubject()).isEqualTo("Alert");
        assertThat(sent.getText()).contains("Body details", "Expire : ", "Examiner dans Kex : https://action.example");
    }
}
