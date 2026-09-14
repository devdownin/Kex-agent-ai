package com.kex.agent.mcp;

import java.util.List;

public record McpServerInfo(String name, String version, String protocolVersion, boolean initialized,
                            List<McpToolInfo> tools) {
}
