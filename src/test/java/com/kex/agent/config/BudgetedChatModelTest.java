// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.agent.BudgetExceededException;
import com.kex.agent.agent.FileBudgetRepository;
import com.kex.agent.agent.TokenBudgetProperties;
import com.kex.agent.agent.TokenBudgetService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BudgetedChatModelTest {
    private TokenBudgetService service(long tokens) {
        return new TokenBudgetService(new TokenBudgetProperties(tokens), Clock.systemUTC(),
                new FileBudgetRepository(new ObjectMapper(), null));
    }
    private Prompt prompt() {
        return new Prompt("hello", ToolCallingChatOptions.builder().toolContext(Map.of("kex.owner", "tenant",
                TokenBudgetService.TASK_KEY, "task")).build());
    }
    private ChatResponse usage(int input, int output) {
        return new ChatResponse(List.of(), ChatResponseMetadata.builder().usage(new DefaultUsage(input, output)).build());
    }
    @Test void each_turn_is_counted_and_the_final_response_does_not_replace_prior_turns() {
        var budget = service(100000);
        var provider = mock(ChatModel.class);
        when(provider.getOptions()).thenReturn(OpenAiChatOptions.builder().maxTokens(100).maxRetries(3).build());
        when(provider.call(any(Prompt.class))).thenReturn(usage(10, 5), usage(20, 10));
        var model = new BudgetedChatModel(provider, () -> budget);
        model.call(prompt()); model.call(prompt());
        assertThat(budget.usage("tenant", "task").tokens()).isEqualTo(45);
        ArgumentCaptor<Prompt> captured = ArgumentCaptor.forClass(Prompt.class);
        verify(provider, times(2)).call(captured.capture());
        assertThat(((OpenAiChatOptions)captured.getValue().getOptions()).getMaxRetries()).isZero();
    }
    @Test void insufficient_budget_never_calls_provider() {
        var budget = service(1); var provider = mock(ChatModel.class);
        when(provider.getOptions()).thenReturn(OpenAiChatOptions.builder().maxTokens(100).build());
        var model = new BudgetedChatModel(provider, () -> budget);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.call(prompt())).isInstanceOf(BudgetExceededException.class);
        verify(provider, never()).call(any(Prompt.class));
        assertThat(budget.consumedToday()).isZero();
    }
    @Test void streaming_usage_snapshots_are_cumulative_and_cancellation_keeps_the_reservation() {
        var budget = service(100000); var provider = mock(ChatModel.class);
        when(provider.getOptions()).thenReturn(OpenAiChatOptions.builder().maxTokens(100).build());
        when(provider.stream(any(Prompt.class))).thenReturn(Flux.just(usage(10, 1), usage(10, 5)));
        var model = new BudgetedChatModel(provider, () -> budget);
        StepVerifier.create(model.stream(prompt())).expectNextCount(2).verifyComplete();
        assertThat(budget.usage("tenant", "task").tokens()).isEqualTo(15);
        when(provider.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(usage(10, 1)), Flux.never()));
        StepVerifier.create(model.stream(prompt())).expectNextCount(1).thenCancel().verify();
        assertThat(budget.usage("tenant", "task").tokens()).isGreaterThan(115);
    }
    @Test void provider_failure_and_missing_usage_remain_charged() {
        var budget = service(100000); var provider = mock(ChatModel.class);
        when(provider.getOptions()).thenReturn(OpenAiChatOptions.builder().maxTokens(100).build());
        when(provider.call(any(Prompt.class))).thenThrow(new IllegalStateException("unavailable"));
        var model = new BudgetedChatModel(provider, () -> budget);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.call(prompt())).isInstanceOf(IllegalStateException.class);
        long first = budget.consumedToday();
        assertThat(first).isGreaterThan(100);
        when(provider.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
        model.call(prompt());
        assertThat(budget.consumedToday()).isEqualTo(2 * first);
    }
}
