package com.kex.agent.agent;

import com.kex.agent.config.AgentProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class AgentService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final Duration timeout;

    AgentService(ChatClient chatClient, ChatMemory chatMemory, AgentProperties properties) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.timeout = properties.requestTimeout();
    }

    /**
     * Le plafond borne l'attente de l'appelant, pas le travail en cours : l'appel bloquant de
     * Spring AI n'est pas interruptible, la tâche continue donc en arrière-plan jusqu'à son terme.
     * Elle tourne sur un thread virtuel, où un tel orphelin coûte une pile, pas un thread noyau.
     */
    public AgentAnswer ask(String conversationId, String message) {
        String id = resolve(conversationId);
        CompletableFuture<String> answer = CompletableFuture.supplyAsync(
                () -> chatClient.prompt()
                        .user(message)
                        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, id))
                        .call()
                        .content(),
                task -> Thread.ofVirtual().name("kex-agent-chat-", 0).start(task));

        try {
            return new AgentAnswer(id, answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        }
        catch (TimeoutException ex) {
            throw new AgentTimeoutException(timeout);
        }
        catch (ExecutionException ex) {
            throw ex.getCause() instanceof RuntimeException cause ? cause : new IllegalStateException(ex.getCause());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** Flux de tokens : l'exécution des outils MCP reste bloquante côté transport. */
    public AgentStream stream(String conversationId, String message) {
        String id = resolve(conversationId);
        Flux<String> content = chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, id))
                .stream()
                .content()
                // Contrairement au chemin bloquant, le timeout annule réellement l'amont.
                .timeout(timeout, Flux.error(new AgentTimeoutException(timeout)));
        return new AgentStream(id, content);
    }

    public void clear(String conversationId) {
        chatMemory.clear(conversationId);
    }

    private static String resolve(String conversationId) {
        return StringUtils.hasText(conversationId) ? conversationId : UUID.randomUUID().toString();
    }
}
