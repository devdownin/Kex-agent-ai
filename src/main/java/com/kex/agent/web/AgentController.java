package com.kex.agent.web;

import com.kex.agent.agent.AgentAnswer;
import com.kex.agent.agent.AgentService;
import com.kex.agent.mcp.McpServerInfo;
import com.kex.agent.mcp.McpToolCatalog;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api/agent")
class AgentController {

    private final AgentService agentService;
    private final McpToolCatalog toolCatalog;

    AgentController(AgentService agentService, McpToolCatalog toolCatalog) {
        this.agentService = agentService;
        this.toolCatalog = toolCatalog;
    }

    @PostMapping("/chat")
    AgentAnswer chat(@Valid @RequestBody ChatRequest request) {
        return agentService.ask(request.conversationId(), request.message());
    }

    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> stream(@Valid @RequestBody ChatRequest request) {
        return agentService.stream(request.conversationId(), request.message());
    }

    @DeleteMapping("/conversations/{conversationId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void clear(@PathVariable String conversationId) {
        agentService.clear(conversationId);
    }

    @GetMapping("/mcp/servers")
    List<McpServerInfo> servers() {
        return toolCatalog.servers();
    }
}
