package com.kex.agent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Service
public class AgentService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    AgentService(ChatClient chatClient, ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
    }

    public AgentAnswer ask(String conversationId, String message) {
        String id = resolve(conversationId);
        String content = chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, id))
                .call()
                .content();
        return new AgentAnswer(id, content);
    }

    /** Flux de tokens : l'exécution des outils MCP reste bloquante côté transport. */
    public Flux<String> stream(String conversationId, String message) {
        String id = resolve(conversationId);
        return chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, id))
                .stream()
                .content();
    }

    public void clear(String conversationId) {
        chatMemory.clear(conversationId);
    }

    private static String resolve(String conversationId) {
        return StringUtils.hasText(conversationId) ? conversationId : UUID.randomUUID().toString();
    }
}
