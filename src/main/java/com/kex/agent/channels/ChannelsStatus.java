// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.channels;

/** Ce qui est actif, jamais l'URL d'un webhook ni un secret. */
public record ChannelsStatus(boolean slack, boolean slackInteractiveButtons, boolean teams,
                             boolean email, int emailRecipients, boolean inboundApproval,
                             boolean consoleUrlConfigured) {
}
