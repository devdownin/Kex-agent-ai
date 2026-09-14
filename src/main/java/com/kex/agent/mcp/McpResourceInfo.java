package com.kex.agent.mcp;

public record McpResourceInfo(String uri, String name, String description, String mimeType, Long size) {
}
