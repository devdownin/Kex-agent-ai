// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import com.anthropic.errors.AnthropicException;
import com.kex.agent.agent.AgentAnswer;
import com.kex.agent.agent.AgentEvent;
import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.AgentStream;
import com.kex.agent.agent.AgentStructuredAnswer;
import com.kex.agent.agent.AgentTimeoutException;
import com.kex.agent.agent.InvalidJsonSchemaException;
import com.kex.agent.agent.StructuredOutputException;
import com.kex.agent.mcp.McpResourceContent;
import com.kex.agent.mcp.McpResourceInfo;
import com.kex.agent.mcp.McpServerInfo;
import com.kex.agent.mcp.McpServerUnavailableException;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.mcp.McpToolResult;
import com.kex.agent.mcp.UnknownMcpServerException;
import com.kex.agent.mcp.UnsupportedMcpCapabilityException;
import com.kex.agent.supervision.SupervisionService;
import com.openai.errors.OpenAIException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.modelcontextprotocol.spec.McpError;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/agent")
class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /**
     * Le disjoncteur `agent-model` a ouvert après une série d'échecs amont : échouer tout de suite
     * plutôt que de laisser chaque appel attendre son propre plafond de temps pour redécouvrir la
     * même panne. Partagé par les deux chemins, pour qu'ils n'en dérivent pas séparément.
     */
    private static final String MODEL_CIRCUIT_OPEN =
            "Le modèle a échoué à plusieurs reprises récemment ; nouvel essai dans quelques instants";

    private final AgentService agentService;
    private final McpToolCatalog toolCatalog;
    private final SupervisionService supervision;

    AgentController(AgentService agentService, McpToolCatalog toolCatalog, SupervisionService supervision) {
        this.agentService = agentService;
        this.toolCatalog = toolCatalog;
        this.supervision = supervision;
    }

    @PostMapping("/chat")
    AgentAnswer chat(@Valid @RequestBody ChatRequest request) {
        return agentService.ask(request.conversationId(), request.message());
    }

    @PostMapping("/chat/structured")
    AgentStructuredAnswer chatStructured(@Valid @RequestBody StructuredChatRequest request) {
        return agentService.askStructured(request.conversationId(), request.message(), request.schema());
    }

    /**
     * Événements nommés plutôt qu'un flux de texte nu : {@code conversation} porte l'identifiant
     * (sans quoi un client qui n'en fournit pas ne peut ni enchaîner ni purger), {@code token} le
     * contenu, {@code tool} l'outil qui vient de s'exécuter — sans quoi le flux reste muet pendant
     * son exécution — et {@code error} un échec, une connexion coupée en silence étant
     * indiscernable d'une réponse complète.
     */
    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest request) {
        AgentStream stream = agentService.stream(request.conversationId(), request.message());
        return Flux.concat(
                        Flux.just(event("conversation", stream.conversationId())),
                        stream.events().map(AgentController::event))
                .onErrorResume(ex -> Flux.just(event("error", streamErrorMessage(ex))));
    }

    private static ServerSentEvent<String> event(AgentEvent agentEvent) {
        return switch (agentEvent) {
            case AgentEvent.Token token -> event("token", token.text());
            case AgentEvent.ToolCall call -> event("tool",
                    "{\"tool\":\"%s\",\"durationMillis\":%d,\"failed\":%b}"
                            .formatted(call.tool(), call.durationMillis(), call.failed()));
        };
    }

    private static ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }

    /**
     * Le détail interne reste dans les journaux : il n'a pas à repartir chez l'appelant. Le
     * disjoncteur ouvert n'est pas un incident à journaliser mais un refus attendu, dit au client
     * dans les mêmes termes que sur le chemin bloquant — la même panne ne se raconte pas de deux
     * façons selon la route empruntée.
     */
    private static String streamErrorMessage(Throwable ex) {
        if (ex instanceof AgentTimeoutException timeout) {
            return timeout.getMessage();
        }
        if (ex instanceof CallNotPermittedException) {
            return MODEL_CIRCUIT_OPEN;
        }
        log.error("Échec pendant le flux de chat", ex);
        return "Le flux s'est interrompu avant la fin de la réponse";
    }

    @DeleteMapping("/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(@PathVariable String conversationId) {
        agentService.clear(conversationId);
    }

    @GetMapping("/mcp/servers")
    List<McpServerInfo> servers() {
        return toolCatalog.servers();
    }

    /**
     * Invocation directe d'un outil MCP, sans passer par le modèle ni par une décision de
     * supervision : aucune capacité ni politique n'encadre cet appel, l'audit est donc la seule
     * trace de qui a appelé quoi (voir {@link SupervisionService#auditAction}, déjà utilisé pour un
     * acte humain hors cycle de supervision par {@code MemoryController}).
     */
    @PostMapping("/mcp/servers/{connection}/tools/{tool}")
    McpToolResult callTool(@PathVariable String connection,
                           @PathVariable String tool,
                           @RequestBody(required = false) McpToolCallRequest request,
                           Principal principal) {
        String actor = actor(principal);
        String action = "Appel MCP direct : " + tool + " sur " + connection;
        try {
            McpToolResult result = toolCatalog.call(connection, tool, request == null ? Map.of() : request.arguments());
            supervision.auditAction(actor, action,
                    result.error() ? "Erreur : " + String.join(" ", result.content()) : "Exécuté");
            return result;
        }
        catch (RuntimeException ex) {
            supervision.auditAction(actor, action, "Échec : " + ex.getMessage());
            throw ex;
        }
    }

    private static String actor(Principal principal) {
        return principal == null ? "Anonyme" : principal.getName();
    }

    @GetMapping("/mcp/servers/{connection}/resources")
    List<McpResourceInfo> resources(@PathVariable String connection) {
        return toolCatalog.resources(connection);
    }

    @GetMapping("/mcp/servers/{connection}/resource")
    List<McpResourceContent> resource(@PathVariable String connection, @RequestParam String uri) {
        return toolCatalog.readResource(connection, uri);
    }

    @ExceptionHandler(InvalidJsonSchemaException.class)
    ProblemDetail invalidSchema(InvalidJsonSchemaException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Le modèle n'a pas tenu le contrat : panne amont, pas erreur d'appelant. */
    @ExceptionHandler(StructuredOutputException.class)
    ProblemDetail structuredOutput(StructuredOutputException ex) {
        log.warn("Sortie structurée non conforme", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    /**
     * L'identifiant de conversation part avec l'échec : l'échange continue en arrière-plan et sa
     * réponse tardive atterrira là. Sans lui, un appelant qui n'en avait pas fourni — le premier
     * message de la console, par exemple — ne pourrait ni reprendre cette conversation ni la
     * purger, alors que son message y est déjà écrit.
     */
    @ExceptionHandler(AgentTimeoutException.class)
    ProblemDetail timeout(AgentTimeoutException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT, ex.getMessage());
        problem.setProperty("conversationId", ex.conversationId());
        return problem;
    }

    @ExceptionHandler(UnknownMcpServerException.class)
    ProblemDetail unknownServer(UnknownMcpServerException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(UnsupportedMcpCapabilityException.class)
    ProblemDetail unsupportedCapability(UnsupportedMcpCapabilityException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, ex.getMessage());
    }

    @ExceptionHandler(McpServerUnavailableException.class)
    ProblemDetail unavailable(McpServerUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(McpError.class)
    ProblemDetail mcpError(McpError ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    /**
     * Sans ce handler, une clé de fournisseur absente ou refusée remontait sans être attrapée —
     * et pour Anthropic, systématiquement en 401, le même code que notre propre bearer rejeté.
     * Un appelant dont le jeton kex.agent.api-key était pourtant valide se voyait répondre comme
     * si ce jeton-là avait été refusé, sans aucun moyen de distinguer les deux. Les deux SDK
     * partagent la même forme : une exception racine par fournisseur, une sous-classe par code
     * HTTP amont — capter la racine couvre l'authentification aussi bien que le rate limit ou une
     * panne du fournisseur, sans avoir à connaître chaque sous-classe.
     */
    @ExceptionHandler({AnthropicException.class, OpenAIException.class})
    ProblemDetail modelProviderFailure(RuntimeException ex) {
        log.warn("Le fournisseur du modèle a refusé ou n'a pas pu traiter l'appel", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    @ExceptionHandler(CallNotPermittedException.class)
    ProblemDetail modelCircuitOpen(CallNotPermittedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, MODEL_CIRCUIT_OPEN);
    }
}
