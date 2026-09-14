package com.kex.agent.web;

import java.util.Map;

public record McpToolCallRequest(Map<String, Object> arguments) {

    public Map<String, Object> arguments() {
        return arguments == null ? Map.of() : arguments;
    }
}
