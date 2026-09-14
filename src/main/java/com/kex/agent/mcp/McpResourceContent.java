// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

/** {@code blob} est le contenu binaire en base64 tel que renvoyé par le serveur MCP. */
public record McpResourceContent(String uri, String mimeType, String text, String blob) {
}
