// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.util.List;

import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.ToolCallRecorder;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Les deux seuls outils locaux du catalogue, à côté de ceux des serveurs MCP — d'où le passage par
 * {@code ToolContext} pour l'identité de conversation et le chronométrage plutôt que par
 * {@code RecordingToolCallbackProvider}, qui n'enveloppe que les outils issus d'un
 * {@code ToolCallbackProvider}. Public : {@code AgentConfig} doit pouvoir la déclarer comme outil
 * du {@code ChatClient}.
 */
public class MemoryTools {

    private final MemoryService memory;

    MemoryTools(MemoryService memory) {
        this.memory = memory;
    }

    @Tool(name = "remember_fact", description = "Retient durablement un fait opérationnel utile aux "
            + "conversations futures (une convention, une contrainte, une correction reçue) — jamais "
            + "un détail propre à cet échange, ni une information déjà disponible ailleurs.")
    public String rememberFact(
            @ToolParam(description = "Le fait à retenir, formulé en une phrase autonome.") String content,
            @ToolParam(required = false, description = "Identifiant, rendu par recall_facts, du "
                    + "souvenir que ce fait corrige. À renseigner dès que le nouveau fait contredit "
                    + "un souvenir existant, sans quoi les deux seront relus ensemble.") String replaces,
            ToolContext toolContext) {
        return ToolCallRecorder.timed(toolContext, "remember_fact",
                () -> memory.remember(content, replaces, conversationId(toolContext)));
    }

    @Tool(name = "recall_facts", description = "Relit les faits retenus lors de conversations "
            + "précédentes, avec leur identifiant. À appeler en début d'échange si un souvenir "
            + "pourrait éviter de redemander une information déjà établie.")
    public List<MemoryFact> recallFacts(ToolContext toolContext) {
        return ToolCallRecorder.timed(toolContext, "recall_facts", memory::recall);
    }

    private static String conversationId(ToolContext toolContext) {
        Object value = toolContext == null ? null
                : toolContext.getContext().get(AgentService.CONVERSATION_ID_CONTEXT_KEY);
        return value instanceof String id ? id : null;
    }
}
