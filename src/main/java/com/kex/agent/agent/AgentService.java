// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.kex.agent.config.AgentProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

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

    public AgentAnswer ask(String conversationId, String message) {
        String id = resolve(conversationId);
        ToolCallRecorder recorder = new ToolCallRecorder();
        String content = bounded(() -> request(id, message, recorder).call().content());
        return new AgentAnswer(id, content, recorder.calls());
    }

    public AgentStructuredAnswer askStructured(String conversationId, String message,
                                               Map<String, Object> schema) {
        if (CollectionUtils.isEmpty(schema)) {
            throw new InvalidJsonSchemaException("Un schéma JSON non vide est requis");
        }
        String id = resolve(conversationId);
        ToolCallRecorder recorder = new ToolCallRecorder();
        Map<String, Object> content = bounded(() -> request(id, message, recorder)
                .call()
                .entity(new JsonSchemaOutputConverter(schema)));
        return new AgentStructuredAnswer(id, content, recorder.calls());
    }

    /**
     * Les événements d'outil et les jetons sont fusionnés dans un seul flux : sans eux, le flux
     * reste muet pendant qu'un outil s'exécute, ce qu'un client ne distingue pas d'un blocage.
     */
    public AgentStream stream(String conversationId, String message) {
        String id = resolve(conversationId);
        Sinks.Many<AgentEvent> tools = Sinks.many().unicast().onBackpressureBuffer();
        ToolCallRecorder recorder = new ToolCallRecorder(tools);

        Flux<AgentEvent> tokens = request(id, message, recorder)
                .stream()
                .content()
                .<AgentEvent>map(AgentEvent.Token::new)
                // Contrairement au chemin bloquant, le timeout annule réellement l'amont.
                .timeout(timeout, Flux.error(new AgentTimeoutException(timeout)))
                .doFinally(signal -> tools.tryEmitComplete());

        return new AgentStream(id, Flux.merge(tools.asFlux(), tokens));
    }

    public void clear(String conversationId) {
        chatMemory.clear(conversationId);
    }

    private ChatClient.ChatClientRequestSpec request(String id, String message, ToolCallRecorder recorder) {
        return chatClient.prompt()
                .user(message)
                .toolContext(Map.of(ToolCallRecorder.CONTEXT_KEY, recorder))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, id));
    }

    /**
     * Le plafond borne l'attente de l'appelant, pas le travail en cours : l'appel bloquant de
     * Spring AI n'est pas interruptible, la tâche continue donc en arrière-plan jusqu'à son terme.
     * Elle tourne sur un thread virtuel, où un tel orphelin coûte une pile, pas un thread noyau.
     */
    private <T> T bounded(Supplier<T> call) {
        CompletableFuture<T> result = CompletableFuture.supplyAsync(call,
                task -> Thread.ofVirtual().name("kex-agent-chat-", 0).start(task));
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
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

    private static String resolve(String conversationId) {
        return StringUtils.hasText(conversationId) ? conversationId : UUID.randomUUID().toString();
    }
}
