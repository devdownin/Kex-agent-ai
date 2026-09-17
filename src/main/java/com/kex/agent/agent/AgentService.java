// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.kex.agent.config.AgentProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Service
public class AgentService {

    /**
     * Clé dans le {@code ToolContext} portant l'identité de conversation, lue par les outils
     * locaux (mémoire) qui doivent savoir de quel échange provient un souvenir. Le préfixe
     * {@code kex.} est filtré avant l'envoi au serveur MCP, comme {@link ToolCallRecorder#CONTEXT_KEY}.
     */
    public static final String CONVERSATION_ID_CONTEXT_KEY = "kex.conversation-id";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final Duration timeout;
    private final CircuitBreaker modelCircuitBreaker;

    AgentService(ChatClient chatClient, ChatMemory chatMemory, AgentProperties properties,
                CircuitBreakerRegistry circuitBreakerRegistry) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.timeout = properties.requestTimeout();
        this.modelCircuitBreaker = circuitBreakerRegistry.circuitBreaker("agent-model");
    }

    /**
     * Le disjoncteur borne les appels bloquants, pas {@link #stream}, dont chaque échec se rend
     * déjà en {@code event: error} sans jamais bloquer un appelant sur le plafond de temps. Pas de
     * réessai ici, contrairement aux appels MCP : un échange qui a déjà exécuté plusieurs tours
     * d'outils le rejouerait en entier, doublant les appels MCP faits jusque-là.
     *
     * <p>La réponse complète est retenue plutôt que son seul texte : le motif d'arrêt et les
     * jetons consommés n'existent nulle part ailleurs à l'échelle d'un échange.
     */
    public AgentAnswer ask(String conversationId, String message) {
        Conversation conversation = conversation(conversationId);
        ToolCallRecorder recorder = new ToolCallRecorder();
        ChatResponse response = bounded(conversation, CircuitBreaker.decorateSupplier(modelCircuitBreaker,
                () -> request(conversation.id(), message, recorder).call().chatResponse()));
        return new AgentAnswer(conversation.id(), text(response), recorder.calls(),
                AgentUsage.from(response), finishReason(response));
    }

    public AgentStructuredAnswer askStructured(String conversationId, String message,
                                               Map<String, Object> schema) {
        if (CollectionUtils.isEmpty(schema)) {
            throw new InvalidJsonSchemaException("Un schéma JSON non vide est requis");
        }
        Conversation conversation = conversation(conversationId);
        ToolCallRecorder recorder = new ToolCallRecorder();
        ResponseEntity<ChatResponse, Map<String, Object>> answer = bounded(conversation,
                CircuitBreaker.decorateSupplier(modelCircuitBreaker,
                        () -> request(conversation.id(), message, recorder).call()
                                .responseEntity(new JsonSchemaOutputConverter(schema))));
        return new AgentStructuredAnswer(conversation.id(), answer.entity(), recorder.calls(),
                AgentUsage.from(answer.response()), finishReason(answer.response()));
    }

    /**
     * Les événements d'outil et les jetons sont fusionnés dans un seul flux : sans eux, le flux
     * reste muet pendant qu'un outil s'exécute, ce qu'un client ne distingue pas d'un blocage.
     */
    public AgentStream stream(String conversationId, String message) {
        String id = conversation(conversationId).id();
        Sinks.Many<AgentEvent> tools = Sinks.many().unicast().onBackpressureBuffer();
        ToolCallRecorder recorder = new ToolCallRecorder(tools);

        // Un plafond de durée, pas de silence : `timeout(Duration)` ne borne que l'attente entre
        // deux jetons, si bien que vingt tours d'outils restant chacun sous le plafond tiendraient
        // la connexion des minutes durant — ce que kex.agent.request-timeout dit empêcher, et ce
        // que spring.mvc.async.request-timeout suppose déjà empêché. Le compte à rebours part de
        // la souscription et couvre tout l'échange, comme sur le chemin bloquant ; contrairement à
        // lui, il annule réellement l'amont.
        Flux<AgentEvent> tokens = request(id, message, recorder)
                .stream()
                .content()
                .<AgentEvent>map(AgentEvent.Token::new)
                .takeUntilOther(Mono.delay(timeout)
                        .flatMap(tick -> Mono.error(new AgentTimeoutException(timeout, id))))
                .doFinally(signal -> tools.tryEmitComplete());

        return new AgentStream(id, Flux.merge(tools.asFlux(), tokens));
    }

    public void clear(String conversationId) {
        chatMemory.clear(conversationId);
    }

    private ChatClient.ChatClientRequestSpec request(String id, String message, ToolCallRecorder recorder) {
        return chatClient.prompt()
                .user(message)
                .toolContext(Map.of(ToolCallRecorder.CONTEXT_KEY, recorder, CONVERSATION_ID_CONTEXT_KEY, id))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, id));
    }

    /**
     * Le plafond borne l'attente de l'appelant, pas le travail en cours : l'appel bloquant de
     * Spring AI n'est pas interruptible, la tâche continue donc en arrière-plan jusqu'à son terme.
     * Elle tourne sur un thread virtuel, où un tel orphelin coûte une pile, pas un thread noyau.
     */
    private <T> T bounded(Conversation conversation, Supplier<T> call) {
        CompletableFuture<T> result = CompletableFuture.supplyAsync(call,
                task -> Thread.ofVirtual().name("kex-agent-chat-", 0).start(task));
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException ex) {
            // L'advisor de mémoire écrit la réponse à la fin de la tâche orpheline, donc après
            // ce que l'appelant a reçu : purger tout de suite la ferait revenir juste après.
            if (conversation.generated()) {
                result.whenComplete((ignored, failure) -> chatMemory.clear(conversation.id()));
            }
            throw new AgentTimeoutException(timeout, conversation.id());
        }
        catch (ExecutionException ex) {
            discardIfUnreachable(conversation);
            throw ex.getCause() instanceof RuntimeException cause ? cause : new IllegalStateException(ex.getCause());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            discardIfUnreachable(conversation);
            throw new IllegalStateException(ex);
        }
    }

    /**
     * L'advisor de mémoire écrit le message de l'utilisateur <em>avant</em> l'appel au modèle : un
     * échange en échec en laisse donc la trace. Quand l'identifiant a été généré ici, personne ne
     * le connaît une fois l'exception remontée — l'entrée resterait en mémoire sans que rien ne
     * puisse la relire ni la purger, et elle survivrait au processus sous le profil
     * {@code shared-memory}. Une conversation que l'appelant a nommée, elle, lui reste accessible :
     * à lui de décider ce qu'elle devient.
     */
    private void discardIfUnreachable(Conversation conversation) {
        if (conversation.generated()) {
            chatMemory.clear(conversation.id());
        }
    }

    /** @param generated l'appelant n'a pas fourni d'identifiant : celui-ci a été tiré ici */
    private record Conversation(String id, boolean generated) {
    }

    private static Conversation conversation(String conversationId) {
        return StringUtils.hasText(conversationId)
                ? new Conversation(conversationId, false)
                : new Conversation(UUID.randomUUID().toString(), true);
    }

    private static String text(ChatResponse response) {
        return Optional.ofNullable(response)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AssistantMessage::getText)
                .orElse(null);
    }

    private static String finishReason(ChatResponse response) {
        return Optional.ofNullable(response)
                .map(ChatResponse::getResult)
                .map(Generation::getMetadata)
                .map(ChatGenerationMetadata::getFinishReason)
                .filter(StringUtils::hasText)
                .orElse(null);
    }
}
