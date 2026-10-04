// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.kex.agent.agent.AgentUsage;
import com.kex.agent.agent.BudgetRepository;
import com.kex.agent.agent.TokenBudgetService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/** At the provider boundary: every tool-loop turn and every fallback attempt is accounted. */
public final class BudgetedChatModel implements ChatModel {
    private final ChatModel delegate;
    private final Supplier<TokenBudgetService> budgets;
    public BudgetedChatModel(ChatModel delegate, Supplier<TokenBudgetService> budgets) {
        this.delegate = delegate; this.budgets = budgets;
    }
    @Override public ChatOptions getOptions() { return delegate.getOptions(); }
    @Override public ChatOptions getDefaultOptions() { return delegate.getOptions(); }
    @Override public ChatResponse call(Prompt prompt) {
        TokenBudgetService service = budgets.get();
        Prompt bounded = prepare(prompt, service);
        BudgetRepository.Reservation reservation = reserve(bounded, service);
        ChatResponse response = delegate.call(bounded);
        service.settle(reservation, AgentUsage.from(response));
        return response;
    }
    @Override public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            TokenBudgetService service = budgets.get();
            Prompt bounded = prepare(prompt, service);
            BudgetRepository.Reservation reservation = reserve(bounded, service);
            AtomicReference<AgentUsage> measured = new AtomicReference<>();
            return delegate.stream(bounded).doOnNext(response -> {
                AgentUsage usage = AgentUsage.from(response);
                // Provider usage chunks are cumulative; never add all streaming snapshots.
                if (usage != null && usage.inputTokens() != null && usage.outputTokens() != null
                        && (measured.get() == null || usage.total() >= measured.get().total())) measured.set(usage);
            }).doOnComplete(() -> service.settle(reservation, measured.get()));
            // Error, cancellation or timeout: do not refund an unknown provider charge.
        });
    }
    private Prompt prepare(Prompt prompt, TokenBudgetService service) {
        ChatOptions defaults = delegate.getOptions();
        if (defaults instanceof org.springframework.ai.openai.OpenAiChatOptions options) {
            var builder = options.mutate();
            if (prompt.getOptions() != null) builder.combineWith(prompt.getOptions().mutate());
            var merged = builder.maxRetries(0).streamUsage(true).build();
            if (merged.getMaxTokens() == null && merged.getMaxCompletionTokens() == null) {
                merged = merged.mutate().maxTokens(service.outputReservation()).build();
            }
            return new Prompt(prompt.getInstructions(), merged);
        }
        if (defaults instanceof org.springframework.ai.anthropic.AnthropicChatOptions options) {
            var builder = options.mutate();
            if (prompt.getOptions() != null) builder.combineWith(prompt.getOptions().mutate());
            var merged = builder.maxRetries(0).build();
            if (merged.getMaxTokens() == null) merged = merged.mutate().maxTokens(service.outputReservation()).build();
            return new Prompt(prompt.getInstructions(), merged);
        }
        return prompt;
    }

    private BudgetRepository.Reservation reserve(Prompt prompt, TokenBudgetService service) {
        Map<String, Object> context = prompt.getOptions() instanceof ToolCallingChatOptions tools
                && tools.getToolContext() != null ? tools.getToolContext() : Map.of();
        String owner = String.valueOf(context.getOrDefault("kex.owner", "kex-internal"));
        String task = String.valueOf(context.getOrDefault(TokenBudgetService.TASK_KEY, UUID.randomUUID().toString()));
        // UTF-8 bytes conservatively bound text tokenization; include tool schemas in the estimate.
        long input = prompt.getInstructions().toString().getBytes(StandardCharsets.UTF_8).length;
        if (prompt.getOptions() instanceof ToolCallingChatOptions tools && tools.getToolCallbacks() != null) {
            for (var callback : tools.getToolCallbacks()) input = Math.addExact(input,
                    callback.getToolDefinition().toString().getBytes(StandardCharsets.UTF_8).length);
        }
        Integer configured = prompt.getOptions() == null ? null : prompt.getOptions().getMaxTokens();
        if (prompt.getOptions() instanceof org.springframework.ai.openai.OpenAiChatOptions openAi
                && openAi.getMaxCompletionTokens() != null) configured = openAi.getMaxCompletionTokens();
        if (configured == null && delegate.getOptions() != null) configured = delegate.getOptions().getMaxTokens();
        long output = configured == null ? service.outputReservation() : configured;
        return service.reserve(owner, task, Math.addExact(input, output), service.cost(input, output));
    }
}
