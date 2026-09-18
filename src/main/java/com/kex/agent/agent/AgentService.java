// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.kex.agent.config.AgentProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
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

    private static final String INTERNAL_OWNER = "kex-internal";

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
    private final TokenBudgetService tokenBudget;

    AgentService(ChatClient chatClient, ChatMemory chatMemory, AgentProperties properties,
                CircuitBreakerRegistry circuitBreakerRegistry, TokenBudgetService tokenBudget) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.timeout = properties.requestTimeout();
        this.modelCircuitBreaker = circuitBreakerRegistry.circuitBreaker("agent-model");
        this.tokenBudget = tokenBudget;
    }

    /**
     * Pas de réessai ici, contrairement aux appels MCP : un échange qui a déjà exécuté plusieurs
     * tours d'outils le rejouerait en entier, doublant les appels MCP faits jusque-là. Le
     * disjoncteur, lui, couvre les deux chemins — voir {@link #stream}.
     *
     * <p>La réponse complète est retenue plutôt que son seul texte : le motif d'arrêt et les
     * jetons consommés n'existent nulle part ailleurs à l'échelle d'un échange.
     */
    public AgentAnswer ask(String conversationId, String message) {
        return ask(INTERNAL_OWNER, conversationId, message);
    }

    public AgentAnswer ask(String owner, String conversationId, String message) {
        Conversation conversation = conversation(owner, conversationId);
        ToolCallRecorder recorder = new ToolCallRecorder();
        AgentExecution execution = new AgentExecution();
        ChatResponse response = bounded(conversation, execution, CircuitBreaker.decorateSupplier(modelCircuitBreaker,
                () -> request(conversation, message, recorder, execution).call().chatResponse()));
        AgentUsage usage = AgentUsage.from(response);
        tokenBudget.record(usage);
        return new AgentAnswer(conversation.id(), text(response), recorder.calls(), usage, finishReason(response));
    }

    public AgentStructuredAnswer askStructured(String conversationId, String message,
                                               Map<String, Object> schema) {
        return askStructured(INTERNAL_OWNER, conversationId, message, schema);
    }

    public AgentStructuredAnswer askStructured(String owner, String conversationId, String message,
                                               Map<String, Object> schema) {
        if (CollectionUtils.isEmpty(schema)) {
            throw new InvalidJsonSchemaException("Un schéma JSON non vide est requis");
        }
        Conversation conversation = conversation(owner, conversationId);
        ToolCallRecorder recorder = new ToolCallRecorder();
        AgentExecution execution = new AgentExecution();
        ResponseEntity<ChatResponse, Map<String, Object>> answer = bounded(conversation, execution,
                CircuitBreaker.decorateSupplier(modelCircuitBreaker,
                        () -> request(conversation, message, recorder, execution).call()
                                .responseEntity(new JsonSchemaOutputConverter(schema))));
        AgentUsage usage = AgentUsage.from(answer.response());
        tokenBudget.record(usage);
        return new AgentStructuredAnswer(conversation.id(), answer.entity(), recorder.calls(),
                usage, finishReason(answer.response()));
    }

    /**
     * Les événements d'outil et les jetons sont fusionnés dans un seul flux : sans eux, le flux
     * reste muet pendant qu'un outil s'exécute, ce qu'un client ne distingue pas d'un blocage.
     *
     * <p>Le même disjoncteur que le chemin bloquant, par l'opérateur réactif : décorer un
     * {@code Supplier} ne couvre pas un flux. Sans lui, un disjoncteur ouvert rendait {@code 503}
     * sur {@code /chat} pendant que {@code /chat/stream} — ce que la console emprunte par défaut —
     * continuait d'envoyer des prompts, et de dépenser, vers un fournisseur déjà constaté en panne.
     */
    public AgentStream stream(String conversationId, String message) {
        return stream(INTERNAL_OWNER, conversationId, message);
    }

    public AgentStream stream(String owner, String conversationId, String message) {
        Conversation conversation = conversation(owner, conversationId);
        String id = conversation.id();
        Sinks.Many<AgentEvent> tools = Sinks.many().unicast().onBackpressureBuffer();
        ToolCallRecorder recorder = new ToolCallRecorder(tools);
        AgentExecution execution = new AgentExecution();

        // Un plafond de durée, pas de silence : `timeout(Duration)` ne borne que l'attente entre
        // deux jetons, si bien que vingt tours d'outils restant chacun sous le plafond tiendraient
        // la connexion des minutes durant — ce que kex.agent.request-timeout dit empêcher, et ce
        // que spring.mvc.async.request-timeout suppose déjà empêché. Le compte à rebours part de
        // la souscription et couvre tout l'échange, comme sur le chemin bloquant ; contrairement à
        // lui, il annule réellement l'amont.
        Flux<AgentEvent> tokens = request(conversation, message, recorder, execution)
                .stream()
                .content()
                .<AgentEvent>map(AgentEvent.Token::new)
                .takeUntilOther(Mono.delay(timeout)
                        .flatMap(tick -> Mono.error(new AgentTimeoutException(timeout, id))))
                // Après le plafond, pour que notre propre timeout compte comme un échec du
                // fournisseur, comme il le fait déjà sur le chemin bloquant. `transformDeferred` :
                // l'état du disjoncteur se lit à la souscription, pas à l'assemblage.
                .transformDeferred(CircuitBreakerOperator.of(modelCircuitBreaker))
                .doFinally(signal -> {
                    execution.cancel();
                    tools.tryEmitComplete();
                });

        return new AgentStream(id, Flux.merge(tools.asFlux(), tokens));
    }

    public void clear(String conversationId) {
        clear(INTERNAL_OWNER, conversationId);
    }

    public void clear(String owner, String conversationId) {
        chatMemory.clear(memoryId(owner, conversationId));
    }

    private ChatClient.ChatClientRequestSpec request(Conversation conversation, String message,
                                                     ToolCallRecorder recorder, AgentExecution execution) {
        return chatClient.prompt()
                .user(message)
                .toolContext(Map.of(ToolCallRecorder.CONTEXT_KEY, recorder,
                        CONVERSATION_ID_CONTEXT_KEY, conversation.id(), AgentExecution.CONTEXT_KEY, execution))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversation.memoryId()));
    }

    /**
     * Le travail tourne sur un thread virtuel dédié : le plafond peut ainsi l'interrompre et
     * invalider la garde transmise aux outils, qui refusent tout nouvel effet après expiration.
     */
    private <T> T bounded(Conversation conversation, AgentExecution execution, Supplier<T> call) {
        FutureTask<T> result = new FutureTask<>(() -> {
            execution.ensureActive();
            return call.get();
        });
        Thread worker = Thread.ofVirtual().name("kex-agent-chat-", 0).start(result);
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException ex) {
            execution.cancel();
            result.cancel(true);
            if (conversation.generated()) {
                Thread.startVirtualThread(() -> {
                    try {
                        worker.join();
                    }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    finally {
                        chatMemory.clear(conversation.memoryId());
                    }
                });
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
            chatMemory.clear(conversation.memoryId());
        }
    }

    /** @param generated l'appelant n'a pas fourni d'identifiant : celui-ci a été tiré ici */
    private record Conversation(String id, String memoryId, boolean generated) {
    }

    private static Conversation conversation(String owner, String conversationId) {
        boolean generated = !StringUtils.hasText(conversationId);
        String id = generated ? UUID.randomUUID().toString() : conversationId;
        return new Conversation(id, memoryId(owner, id), generated);
    }

    private static String memoryId(String owner, String conversationId) {
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("Un propriétaire et un identifiant de conversation sont requis");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((owner + "\u0000" + conversationId).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponible", ex);
        }
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
