// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.workspace;

import java.time.Instant;
import java.util.List;

import com.kex.agent.agent.AgentEvent;
import com.kex.agent.knowledge.KnowledgeSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkspaceRequest(String id, long revision, String title, String conversationId,
        String status, Instant updatedAt, List<Turn> turns, List<AgentEvent.ToolCall> tools,
        Context context, String taskId) {
    public record Turn(String role, String text, boolean completed, List<KnowledgeSource> sources, String error) { }
    public record Attachment(@NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 5000) String text) { }
    public record Context(@Size(max = 500) String process, @Size(max = 500) String period,
            @Size(max = 200) String environment, @Size(max = 3) List<@Valid Attachment> files) { }
    public record Input(String id, @NotBlank @Size(max = 24000) String message, @Valid Context context) { }
}
