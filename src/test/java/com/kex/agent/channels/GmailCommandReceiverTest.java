// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.time.Duration;
import java.util.Set;

import com.kex.agent.agent.AgentAnswer;
import com.kex.agent.agent.AgentService;
import jakarta.mail.Flags;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GmailCommandReceiverTest {
    private final AgentService agent = mock(AgentService.class);
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final GmailCommandReceiver receiver = new GmailCommandReceiver(
            new GmailCommandProperties(true, "kex@gmail.com", "secret", "kex@gmail.com",
                    Set.of("ops@example.com"), Set.of("kafka_read"), Duration.ofMinutes(1)), agent, mail);

    @Test
    void executes_plain_text_command_and_sends_answer_to_subject_address() throws Exception {
        MimeMessage message = message("Ops <ops@example.com>", "to Kex from ops@example.com", "Quel est le lag ?");
        when(agent.askReadOnly(eq("gmail:ops@example.com"), isNull(), eq("Quel est le lag ?"),
                eq(Set.of("kafka_read")))).thenReturn(new AgentAnswer("id", "Lag: 0", null, null, null));

        receiver.process(message);

        org.mockito.ArgumentCaptor<SimpleMailMessage> sent = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("ops@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("Re: to Kex from ops@example.com");
        assertThat(sent.getValue().getText()).isEqualTo("Lag: 0");
        assertThat(message.isSet(Flags.Flag.SEEN)).isTrue();
    }

    @Test
    void refuses_redirect_and_unlisted_sender_without_executing_prompt() throws Exception {
        MimeMessage redirect = message("ops@example.com", "to Kex from other@example.com", "prompt");
        MimeMessage unknown = message("unknown@example.com", "to Kex from unknown@example.com", "prompt");
        receiver.process(redirect);
        receiver.process(unknown);
        verifyNoInteractions(agent, mail);
        assertThat(redirect.isSet(Flags.Flag.SEEN)).isTrue();
        assertThat(unknown.isSet(Flags.Flag.SEEN)).isTrue();
    }

    @Test
    void leaves_failed_delivery_unread_for_retry() throws Exception {
        MimeMessage message = message("ops@example.com", "to Kex from ops@example.com", "prompt");
        when(agent.askReadOnly(any(), isNull(), any(), any())).thenReturn(new AgentAnswer("id", "answer", null, null, null));
        doThrow(new org.springframework.mail.MailSendException("smtp unavailable"))
                .when(mail).send(any(SimpleMailMessage.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> receiver.process(message))
                .isInstanceOf(org.springframework.mail.MailSendException.class);
        assertThat(message.isSet(Flags.Flag.SEEN)).isFalse();
    }

    private static MimeMessage message(String from, String subject, String prompt) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new java.util.Properties()));
        message.setFrom(new jakarta.mail.internet.InternetAddress(from));
        message.setSubject(subject);
        message.setText(prompt);
        message.saveChanges();
        return message;
    }
}
