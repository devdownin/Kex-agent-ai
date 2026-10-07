// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.kex.agent.config.AgentProperties;
import com.kex.agent.memory.LongTermMemoryService;
import com.kex.agent.tools.ToolSelectionService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
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
    private final String systemPrompt;
    private final CircuitBreaker modelCircuitBreaker;
    private final TokenBudgetService tokenBudget;
    private final LongTermMemoryService longTermMemory;
    private final ToolSelectionService toolSelection;
    private com.kex.agent.knowledge.KnowledgeService knowledge;
    @Autowired
    void knowledge(org.springframework.beans.factory.ObjectProvider<com.kex.agent.knowledge.KnowledgeService> provider) {
        knowledge = provider.getIfAvailable();
    }

    @Autowired
    AgentService(ChatClient chatClient, ChatMemory chatMemory, AgentProperties properties,
                CircuitBreakerRegistry circuitBreakerRegistry, TokenBudgetService tokenBudget,
                org.springframework.beans.factory.ObjectProvider<LongTermMemoryService> longTermMemory,
                ToolSelectionService toolSelection) {
        this(chatClient, chatMemory, properties, circuitBreakerRegistry, tokenBudget,
                longTermMemory.getIfAvailable(), toolSelection);
    }

    AgentService(ChatClient chatClient, ChatMemory chatMemory, AgentProperties properties,
                 CircuitBreakerRegistry circuitBreakerRegistry, TokenBudgetService tokenBudget,
                 org.springframework.beans.factory.ObjectProvider<LongTermMemoryService> longTermMemory) {
        this(chatClient, chatMemory, properties, circuitBreakerRegistry, tokenBudget,
                longTermMemory.getIfAvailable(), null);
    }

    /** Compatibility constructor for focused unit tests and minimal embedding applications. */
    AgentService(ChatClient chatClient, ChatMemory chatMemory, AgentProperties properties,
                 CircuitBreakerRegistry circuitBreakerRegistry, TokenBudgetService tokenBudget) {
        this(chatClient, chatMemory, properties, circuitBreakerRegistry, tokenBudget, (LongTermMemoryService) null, null);
    }

    private AgentService(ChatClient chatClient, ChatMemory chatMemory, AgentProperties properties,
                         CircuitBreakerRegistry circuitBreakerRegistry, TokenBudgetService tokenBudget,
                         LongTermMemoryService longTermMemory, ToolSelectionService toolSelection) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.timeout = properties.requestTimeout();
        this.systemPrompt = properties.systemPrompt();
        this.modelCircuitBreaker = circuitBreakerRegistry.circuitBreaker("agent-model");
        this.tokenBudget = tokenBudget;
        this.longTermMemory = longTermMemory;
        this.toolSelection = toolSelection;
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
        return askForTask(owner, conversationId, message, "CHAT");
    }

    public AgentAnswer askForTask(String owner, String conversationId, String message, String task) {
        return ask(conversation(owner, conversationId, task, null), message);
    }

    /** Only the server-configured allowlist can authorize tools for an unattended task. */
    public AgentAnswer askReadOnly(String owner, String conversationId, String message, Set<String> allowedTools) {
        return ask(conversation(owner, conversationId, "TRIAGE", Set.copyOf(allowedTools)), message);
    }

    private AgentAnswer ask(Conversation conversation, String message) {
        ToolCallRecorder recorder = new ToolCallRecorder();
        AgentExecution execution = new AgentExecution();
        ChatResponse response = bounded(conversation, execution, CircuitBreaker.decorateSupplier(modelCircuitBreaker,
                () -> request(conversation, message, recorder, execution).call().chatResponse()));
        AgentUsage usage = AgentUsage.from(response);
        if (longTermMemory != null) {
            longTermMemory.recordSuccessfulTask(conversation.owner(), conversation.id(), message, text(response), recorder.calls());
        }
        return new AgentAnswer(conversation.id(), text(response), recorder.calls(), usage, finishReason(response), conversation.sources());
    }

    public AgentStructuredAnswer askStructured(String conversationId, String message,
                                               Map<String, Object> schema) {
        return askStructured(INTERNAL_OWNER, conversationId, message, schema);
    }

    public AgentStructuredAnswer askStructured(String owner, String conversationId, String message,
                                               Map<String, Object> schema) {
        return askStructured(conversation(owner, conversationId, "DIAGNOSTIC", null), message, schema);
    }

    /** La liste vient du serveur ; vide, elle empêche tout appel pendant la planification. */
    public AgentStructuredAnswer askStructuredReadOnly(String owner, String conversationId, String message,
                                                       Map<String, Object> schema, Set<String> allowedTools) {
        return askStructured(conversation(owner, conversationId, "DIAGNOSTIC", Set.copyOf(allowedTools)),
                message, schema);
    }

    private AgentStructuredAnswer askStructured(Conversation conversation, String message,
                                                Map<String, Object> schema) {
        if (CollectionUtils.isEmpty(schema)) {
            throw new InvalidJsonSchemaException("Un schéma JSON non vide est requis");
        }
        ToolCallRecorder recorder = new ToolCallRecorder();
        AgentExecution execution = new AgentExecution();
        ResponseEntity<ChatResponse, Map<String, Object>> answer = bounded(conversation, execution,
                CircuitBreaker.decorateSupplier(modelCircuitBreaker,
                        () -> request(conversation, message, recorder, execution).call()
                                .responseEntity(new JsonSchemaOutputConverter(schema))));
        AgentUsage usage = AgentUsage.from(answer.response());
        if (longTermMemory != null) {
            longTermMemory.recordSuccessfulTask(conversation.owner(), conversation.id(), message, text(answer.response()), recorder.calls());
        }
        return new AgentStructuredAnswer(conversation.id(), answer.entity(), recorder.calls(),
                usage, finishReason(answer.response()), conversation.sources());
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
        return streamForTask(owner, conversationId, message, "CHAT");
    }

    public AgentStream streamForTask(String owner, String conversationId, String message, String task) {
        return streamConversation(conversation(owner, conversationId, task, null), message);
    }
    public AgentStream streamReadOnly(String owner, String conversationId, String message, Set<String> allowedTools) {
        return streamConversation(conversation(owner, conversationId, "CHAT", Set.copyOf(allowedTools)), message);
    }
    private AgentStream streamConversation(Conversation conversation, String message) {
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
        StringBuilder answer = new StringBuilder();
        Flux<AgentEvent> tokens = request(conversation, message, recorder, execution)
                .stream()
                .content()
                .doOnNext(answer::append)
                .<AgentEvent>map(AgentEvent.Token::new)
                .takeUntilOther(Mono.delay(timeout)
                        .flatMap(tick -> Mono.error(new AgentTimeoutException(timeout, id))))
                // Après le plafond, pour que notre propre timeout compte comme un échec du
                // fournisseur, comme il le fait déjà sur le chemin bloquant. `transformDeferred` :
                // l'état du disjoncteur se lit à la souscription, pas à l'assemblage.
                .transformDeferred(CircuitBreakerOperator.of(modelCircuitBreaker))
                .doFinally(signal -> {
                    execution.cancel();
                    // Seul un flux qui va jusqu'à son terme normal compte comme un échange réussi :
                    // une erreur, un timeout ou une déconnexion client (`ON_COMPLETE` est le seul
                    // signal qui l'atteste) ne doivent pas nourrir la mémoire long-terme d'une
                    // réponse tronquée. Sans cette écriture, /chat/stream — le chemin que la
                    // console emprunte par défaut — n'alimentait jamais ce second système de
                    // mémoire, contrairement à ask/askStructured.
                    if (longTermMemory != null && signal == SignalType.ON_COMPLETE) {
                        longTermMemory.recordSuccessfulTask(conversation.owner(), conversation.id(), message,
                                answer.toString(), recorder.calls());
                    }
                    tools.tryEmitComplete();
                });

        return new AgentStream(id, Flux.concat(Flux.defer(() -> conversation.sources().isEmpty() ? Flux.empty()
                : Flux.just(new AgentEvent.Sources(conversation.sources()))), Flux.merge(tools.asFlux(), tokens)));
    }

    public void clear(String conversationId) {
        clear(INTERNAL_OWNER, conversationId);
    }

    public void clear(String owner, String conversationId) {
        chatMemory.clear(knowledgeMemoryId(memoryId(owner, conversationId), com.kex.agent.knowledge.KnowledgeAccess.current(owner)));
    }

    private ChatClient.ChatClientRequestSpec request(Conversation conversation, String message,
                                                     ToolCallRecorder recorder, AgentExecution execution) {
        Map<String, Object> context = new HashMap<>();
        context.put(ToolCallRecorder.CONTEXT_KEY, recorder);
        context.put(CONVERSATION_ID_CONTEXT_KEY, conversation.id());
        context.put(AgentExecution.CONTEXT_KEY, execution);
        context.put("kex.owner", conversation.owner());
        context.put(TokenBudgetService.CONTEXT_KEY, tokenBudget);
        context.put(TokenBudgetService.TASK_KEY, UUID.randomUUID().toString());
        context.put("kex.model-task", conversation.task());
        if (conversation.allowedTools() != null) {
            context.put(TaskToolPolicy.ALLOWED_TOOLS, conversation.allowedTools());
        }
        ChatClient.ChatClientRequestSpec request = chatClient.prompt().user(message);
        if (toolSelection != null) {
            var selected = toolSelection.select(message, conversation.allowedTools());
            request = request.toolCallbacks(selected.toArray(org.springframework.ai.tool.ToolCallback[]::new));
            // Une seconde garde limite aussi les tours suivants et les appels issus d'une ancienne mémoire.
            context.put(TaskToolPolicy.ALLOWED_TOOLS, selected.stream()
                    .map(tool -> tool.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet()));
        }
        String kafkaProcedure = KafkaOperationalPlaybooks.forRequest(message);
        String durable = longTermMemory == null ? "" : longTermMemory.context(conversation.owner());
        java.util.List<com.kex.agent.knowledge.KnowledgeMatch> matches = knowledge == null ? java.util.List.of()
                : knowledge.search(message, null, conversation.knowledgeAccess());
        conversation.evidence().set(matches.stream().map(com.kex.agent.knowledge.KnowledgeSource::from).toList());
        String references = knowledge == null ? "" : knowledge.contextFrom(matches);
        if (!kafkaProcedure.isBlank() || !durable.isBlank() || !references.isBlank()) {
            // system(...) remplace le prompt du ChatClient ; conserver la gouvernance avant les références.
            request = request.system(systemPrompt + "\n\n" + kafkaProcedure + "\n\n" + durable + "\n\n" + references);
        }
        return request.toolContext(context)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, knowledgeMemoryId(conversation.memoryId(), conversation.knowledgeAccess())));
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
                        chatMemory.clear(knowledgeMemoryId(conversation.memoryId(), conversation.knowledgeAccess()));
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
            chatMemory.clear(knowledgeMemoryId(conversation.memoryId(), conversation.knowledgeAccess()));
        }
    }

    /** @param generated l'appelant n'a pas fourni d'identifiant : celui-ci a été tiré ici */
    private record Conversation(String id, String memoryId, boolean generated, String owner,
                                String task, Set<String> allowedTools, com.kex.agent.knowledge.KnowledgeAccess knowledgeAccess,
                                java.util.concurrent.atomic.AtomicReference<java.util.List<com.kex.agent.knowledge.KnowledgeSource>> evidence) {
        java.util.List<com.kex.agent.knowledge.KnowledgeSource> sources() { return evidence.get(); }
    }

    private static Conversation conversation(String owner, String conversationId) {
        return conversation(owner, conversationId, "CHAT", null);
    }

    private static Conversation conversation(String owner, String conversationId, String task, Set<String> allowedTools) {
        String route = StringUtils.hasText(task) ? task.toUpperCase(java.util.Locale.ROOT) : "CHAT";
        if (!Set.of("CHAT", "TRIAGE", "DIAGNOSTIC").contains(route)) {
            throw new IllegalArgumentException("Type de tâche inconnu : " + task);
        }
        boolean generated = !StringUtils.hasText(conversationId);
        String id = generated ? UUID.randomUUID().toString() : conversationId;
        return new Conversation(id, memoryId(owner, id), generated, owner, route, allowedTools, com.kex.agent.knowledge.KnowledgeAccess.current(owner), new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of()));
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

    private String knowledgeMemoryId(String id, com.kex.agent.knowledge.KnowledgeAccess access) {
        return knowledge == null ? id : memoryId(id, "knowledge:" + access.roles().stream().sorted().collect(java.util.stream.Collectors.joining(",")));
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
