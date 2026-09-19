// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.client.RestClientResponseException;
import reactor.core.publisher.Flux;

/**
 * One provider attempt per model turn. Spring AI 2 executes tools in ToolCallingAdvisor,
 * outside this boundary: failover never replays the conversation or an executed tool.
 */
public final class RoutingChatModel implements ChatModel {

    public static final String TASK_CONTEXT_KEY = "kex.model-task";
    private static final Logger log = LoggerFactory.getLogger(RoutingChatModel.class);
    private final LlmRoutingProperties properties;
    private final Map<String, ChatModel> models;

    RoutingChatModel(LlmRoutingProperties properties, Map<String, ChatModel> models) {
        this.properties = properties;
        this.models = Map.copyOf(models);
    }

    @Override
    public ChatOptions getOptions() {
        // Advertise both tool and structured-output support without leaking endpoint credentials
        // or pinning the primary model into the next provider's request options.
        return OpenAiChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        List<String> route = route(prompt);
        for (int index = 0; index < route.size(); index++) {
            String name = route.get(index);
            try {
                return models.get(name).call(forEndpoint(prompt, name));
            }
            catch (RuntimeException failure) {
                if (index + 1 == route.size() || !eligible(failure)) throw failure;
                log.warn("Model endpoint {} unavailable; trying {}", name, route.get(index + 1));
            }
        }
        throw new IllegalStateException("No model route configured");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> streamAttempt(prompt, route(prompt), 0));
    }

    private Flux<ChatResponse> streamAttempt(Prompt prompt, List<String> route, int index) {
        AtomicBoolean emitted = new AtomicBoolean();
        String name = route.get(index);
        return Flux.defer(() -> models.get(name).stream(forEndpoint(prompt, name)))
                .doOnNext(response -> emitted.set(true))
                .onErrorResume(failure -> {
                    // Even an empty metadata chunk prevents fallback: subscribers may already
                    // have observed it. Per-subscription state also prevents cross-request races.
                    if (emitted.get() || index + 1 == route.size() || !eligible(failure)) {
                        return Flux.error(failure);
                    }
                    log.warn("Streaming model endpoint {} unavailable; trying {}", name, route.get(index + 1));
                    return streamAttempt(prompt, route, index + 1);
                });
    }

    private List<String> route(Prompt prompt) {
        Object requested = prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolContext() != null ? options.getToolContext().get(TASK_CONTEXT_KEY) : null;
        LlmRoutingProperties.Task task = requested == null ? LlmRoutingProperties.Task.CHAT
                : LlmRoutingProperties.Task.valueOf(requested.toString().toUpperCase(Locale.ROOT));
        return properties.route(task);
    }

    private Prompt forEndpoint(Prompt prompt, String name) {
        ChatOptions original = prompt.getOptions();
        // Portable options only. Copying a provider-specific object could transfer its API key,
        // URL or model to the fallback. Tool callbacks, context and JSON schema remain intact.
        var portable = OpenAiChatOptions.builder().model(properties.endpoints().get(name).model());
        if (original instanceof ToolCallingChatOptions tools) {
            portable.toolCallbacks(tools.getToolCallbacks()).toolContext(tools.getToolContext());
        }
        // Structured output options are carried by the original prompt when the provider supports
        // them; routing never copies credentials or endpoint-specific fields into the fallback.
        return new Prompt(prompt.getInstructions(), portable.build());
    }

    static boolean eligible(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) return false;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean transportFailure = false;
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof CancellationException || cause instanceof InterruptedException) return false;
            if (cause instanceof OpenAIServiceException service) return retryableStatus(service.statusCode());
            if (cause instanceof AnthropicServiceException service) return retryableStatus(service.statusCode());
            if (cause instanceof RestClientResponseException service) return retryableStatus(service.getStatusCode().value());
            if (cause instanceof OpenAIIoException || cause instanceof AnthropicIoException
                    || cause instanceof ConnectException || cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException || cause instanceof UnknownHostException) {
                transportFailure = true;
            }
        }
        return transportFailure;
    }

    private static boolean retryableStatus(int status) {
        return status == 429 || status >= 500 && status <= 599;
    }
}
