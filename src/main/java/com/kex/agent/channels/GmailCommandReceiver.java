// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

import com.kex.agent.agent.AgentService;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.SubjectTerm;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;

/** Interroge Gmail en TLS ; seules les commandes texte autorisées atteignent l'agent. */
final class GmailCommandReceiver {
    private static final Logger log = LoggerFactory.getLogger(GmailCommandReceiver.class);
    private static final String PREFIX = "to Kex from ";
    private static final int MAX_PROMPT_CHARS = 16_000;

    private final GmailCommandProperties properties;
    private final AgentService agent;
    private final JavaMailSender sender;

    GmailCommandReceiver(GmailCommandProperties properties, AgentService agent, JavaMailSender sender) {
        this.properties = properties;
        this.agent = agent;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${kex.agent.gmail.poll-interval:1m}")
    void poll() {
        Properties settings = new Properties();
        settings.setProperty("mail.store.protocol", "imaps");
        settings.setProperty("mail.imaps.host", "imap.gmail.com");
        settings.setProperty("mail.imaps.port", "993");
        settings.setProperty("mail.imaps.ssl.enable", "true");
        settings.setProperty("mail.imaps.connectiontimeout", "10000");
        settings.setProperty("mail.imaps.timeout", "10000");
        Store store = null;
        Folder inbox = null;
        try {
            store = Session.getInstance(settings).getStore("imaps");
            store.connect("imap.gmail.com", properties.username(), properties.appPassword());
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            for (Message message : inbox.search(new AndTerm(
                    new FlagTerm(new Flags(Flags.Flag.SEEN), false), new SubjectTerm(PREFIX)))) {
                try {
                    process(message);
                }
                catch (Exception ex) {
                    // Un échec SMTP laisse le message non lu pour une nouvelle tentative.
                    log.warn("Commande Gmail non traitée ; nouvelle tentative au prochain passage ({})",
                            ex.getClass().getSimpleName());
                }
            }
        }
        catch (MessagingException ex) {
            log.warn("Lecture Gmail indisponible ; nouvelle tentative au prochain passage ({})",
                    ex.getClass().getSimpleName());
        }
        finally {
            try {
                if (inbox != null && inbox.isOpen()) inbox.close(false);
                if (store != null && store.isConnected()) store.close();
            }
            catch (MessagingException ex) {
                log.warn("Fermeture de la connexion Gmail impossible ({})", ex.getClass().getSimpleName());
            }
        }
    }

    void process(Message message) throws MessagingException, IOException {
        String subject = message.getSubject();
        if (subject == null || !subject.startsWith(PREFIX)) return;
        String replyTo = validAddress(subject.substring(PREFIX.length()));
        Address[] from = message.getFrom();
        String senderAddress = from != null && from.length == 1 && from[0] instanceof InternetAddress address
                ? validAddress(address.getAddress()) : null;
        if (replyTo == null || senderAddress == null || !senderAddress.equalsIgnoreCase(replyTo)
                || properties.allowedSenders().stream().noneMatch(senderAddress::equalsIgnoreCase)) {
            log.warn("Commande Gmail refusée : expéditeur ou adresse de réponse non autorisé");
            message.setFlag(Flags.Flag.SEEN, true);
            return;
        }
        if (!message.isMimeType("text/plain") || message.getSize() > 64_000) {
            log.warn("Commande Gmail refusée : corps non texte ou trop volumineux");
            message.setFlag(Flags.Flag.SEEN, true);
            return;
        }
        Object content = message.getContent();
        if (!(content instanceof String prompt) || prompt.isBlank() || prompt.length() > MAX_PROMPT_CHARS) {
            log.warn("Commande Gmail refusée : prompt vide ou trop long");
            message.setFlag(Flags.Flag.SEEN, true);
            return;
        }
        String answer = agent.askReadOnly("gmail:" + senderAddress.toLowerCase(Locale.ROOT), null,
                prompt, properties.readOnlyTools()).content();
        SimpleMailMessage reply = new SimpleMailMessage();
        reply.setFrom(properties.from());
        reply.setTo(replyTo);
        reply.setSubject("Re: " + subject);
        reply.setText(answer);
        sender.send(reply);
        message.setFlag(Flags.Flag.SEEN, true);
    }

    private static String validAddress(String value) {
        try {
            if (value == null || !value.equals(value.strip()) || value.contains("\r") || value.contains("\n")) return null;
            InternetAddress[] addresses = InternetAddress.parse(value, true);
            if (addresses.length != 1 || !value.equals(addresses[0].getAddress()) || !value.contains("@")) return null;
            addresses[0].validate();
            return value;
        }
        catch (MessagingException ex) {
            return null;
        }
    }
}
