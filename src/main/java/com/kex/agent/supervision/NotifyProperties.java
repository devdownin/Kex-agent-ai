// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Débouché par défaut de la capacité {@link Capability#NOTIFY} quand aucun outil MCP ne lui est
 * lié. Vide par défaut : une installation qui ne le renseigne pas ne voit rien partir.
 */
@ConfigurationProperties("kex.agent.supervision.notify")
public record NotifyProperties(@DefaultValue("") String webhookUrl, @DefaultValue("") String webhookToken) {
}
