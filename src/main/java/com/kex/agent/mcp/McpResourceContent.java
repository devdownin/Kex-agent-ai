package com.kex.agent.mcp;

/** {@code blob} est le contenu binaire en base64 tel que renvoyé par le serveur MCP. */
public record McpResourceContent(String uri, String mimeType, String text, String blob) {
}
