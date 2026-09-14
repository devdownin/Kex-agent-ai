package com.kex.agent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(String conversationId,
                          @NotBlank @Size(max = 32_000) String message) {
}
