// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Plain text only; sender and recipients are operator configuration. */
public final class EmailChannelAdapter implements ChannelAdapter {
    private final JavaMailSender sender;
    private final ChannelProperties.Email properties;

    public EmailChannelAdapter(JavaMailSender sender, ChannelProperties.Email properties) {
        this.sender = sender;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public void send(ChannelMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.from());
        mail.setTo(properties.recipients().toArray(String[]::new));
        mail.setSubject(message.subject());
        mail.setText(message.body()
                + (message.expiresAt() == null ? "" : "\n\nExpire : " + message.expiresAt())
                + (message.actionUrl() == null ? "" : "\n\nExaminer dans Kex : " + message.actionUrl()));
        sender.send(mail);
    }
}
