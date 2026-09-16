// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import com.anthropic.errors.AnthropicException;
import com.kex.agent.agent.AgentTimeoutException;
import com.kex.agent.mcp.McpServerUnavailableException;
import com.openai.errors.OpenAIException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pas le starter {@code resilience4j-spring-boot3} : il vérifie explicitement la version de
 * Spring Boot au démarrage et refuse Spring Boot 4. Les registres sont donc construits ici, à la
 * main, et {@link com.kex.agent.mcp.McpToolCatalog} et {@code AgentService} y puisent le
 * disjoncteur et le réessai nommés qui les concernent plutôt que de les recevoir par annotation.
 */
@Configuration(proxyBeanMethods = false)
class ResilienceConfig {

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(ResilienceProperties properties, MeterRegistry meterRegistry) {
        CircuitBreakerConfig defaults = CircuitBreakerConfig.custom()
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(defaults);
        // Une erreur de validation (schéma vide, connexion inconnue) n'est pas une panne du
        // fournisseur : seules les exceptions remontées par les SDK Anthropic/OpenAI et notre
        // propre plafond de temps comptent contre ce disjoncteur.
        registry.circuitBreaker("agent-model", CircuitBreakerConfig.from(defaults)
                .recordExceptions(AnthropicException.class, OpenAIException.class, AgentTimeoutException.class)
                .build());
        registry.circuitBreaker("mcp-tool");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    RetryRegistry retryRegistry(ResilienceProperties properties, MeterRegistry meterRegistry) {
        RetryRegistry registry = RetryRegistry.ofDefaults();
        // Seule l'indisponibilité explicite d'un serveur MCP est retentée : une erreur de
        // protocole (outil inconnu, argument refusé) resterait fausse en la rejouant.
        registry.retry("mcp-tool", RetryConfig.custom()
                .maxAttempts(properties.retryMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(properties.retryWaitDuration(), 2.0))
                .retryExceptions(McpServerUnavailableException.class)
                .build());
        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meterRegistry);
        return registry;
    }
}
