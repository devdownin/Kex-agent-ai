// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.kex.agent.supervision.eval.AgentEvalCorpus;
import com.kex.agent.supervision.eval.AgentEvalMcpServer;
import com.kex.agent.supervision.eval.AgentEvalReport;
import com.kex.agent.supervision.eval.AgentEvalScoring;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Paid, stochastic evaluation of actual production supervision and real model/tool execution. */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "KEX_RUN_AGENT_EVALS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mcp-it")
class AgentReliabilityEvalTest {
    private static final AgentEvalMcpServer SERVER = start();
    private static final String PROVIDER = System.getenv().getOrDefault("KEX_AGENT_LLM_PROVIDER", "anthropic");
    private static final String MODEL = requiredEnvironment("KEX_EVAL_MODEL");

    @Autowired
    SupervisionService supervision;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    private static AgentEvalMcpServer start() {
        try {
            return new AgentEvalMcpServer();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required environment: " + name);
        return value;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (!List.of("anthropic", "openai").contains(PROVIDER)) {
            throw new IllegalStateException("Evaluation provider must be anthropic or openai");
        }
        String key = requiredEnvironment(PROVIDER.equals("anthropic") ? "ANTHROPIC_API_KEY" : "OPENROUTER_API_KEY");
        registry.add("spring.ai.model.chat", () -> PROVIDER);
        registry.add("spring.ai." + PROVIDER + ".api-key", () -> key);
        registry.add("spring.ai." + PROVIDER + ".chat.options.model", () -> MODEL);
        registry.add("spring.ai.tools.limits.max-total-tool-calls", () -> 8);
        registry.add("kex.agent.request-timeout", () -> "90s");
        registry.add("kex.agent.log-interactions", () -> false);
        registry.add("kex.agent.memory.enabled", () -> false);
        registry.add("kex.agent.token-budget.daily-limit", () -> 0);
        registry.add("kex.agent.supervision.notify.webhook-url", () -> "");
        registry.add("kex.agent.supervision.learning.enabled", () -> false);
        String url = "http://127.0.0.1:" + SERVER.port();
        registry.add("spring.ai.mcp.client.streamable-http.connections.eval.url", () -> url);
        registry.add("spring.ai.mcp.client.streamable-http.connections.eval.endpoint", () -> "/mcp");
        registry.add("kex.mcp.bearer-tokens[0].url-prefix", () -> url);
        registry.add("kex.mcp.bearer-tokens[0].token", () -> AgentEvalMcpServer.TOKEN);
        registry.add("kex.agent.supervision.processes[0].id", () -> "order-integration");
        registry.add("kex.agent.supervision.processes[0].name", () -> "Order Integration");
        registry.add("kex.agent.supervision.processes[0].hint", () ->
                "Mesure actuelle du topic orders, groupe order-consumer. Préférer kex_diagnose_consumer "
                        + "si annoncé, sinon kex_consumer_lag. "
                        + "Les prévisions disponibles concernent aussi ces topic et groupe.");
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Test
    void evaluatesRealModelAcrossRepeatedNetworkFixturesAndWritesReviewableReport() throws Exception {
        int repetitions = Integer.parseInt(System.getenv().getOrDefault("KEX_EVAL_REPETITIONS", "3"));
        assertThat(repetitions).isBetween(2, 10);
        List<AgentEvalCorpus.Scenario> corpus = AgentEvalCorpus.load();
        List<AgentEvalScoring.Trial> trials = new ArrayList<>();
        Path reportFile = Path.of(System.getenv().getOrDefault("KEX_EVAL_REPORT", "target/agent-evals/candidate.json"));
        for (AgentEvalCorpus.Scenario scenario : corpus) {
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                // A deliberate MCP outage must not poison the next independent fixture's circuit.
                circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
                SERVER.select(scenario);
                CycleReport cycle = supervision.runCycle("agent-evaluation");
                ProcessSnapshot snapshot = supervision.snapshots().stream()
                        .filter(value -> "order-integration".equals(value.processId())).findFirst().orElseThrow();
                var observation = new AgentEvalScoring.Observation(snapshot.state().name(),
                        snapshot.coverage().complete(), snapshot.coverage().knownIncomplete(),
                        snapshot.coverage().notReached(), cycle.anomaliesDetected(), cycle.failure(), SERVER.calls());
                trials.add(AgentEvalScoring.score(scenario, repetition, observation));
                // Persist completed trials even if a later call crashes or the workflow times out.
                report(repetitions, trials).write(reportFile);
            }
        }
        AgentEvalReport report = report(repetitions, trials);
        String baselinePath = System.getenv("KEX_EVAL_BASELINE");
        AgentEvalReport baseline = baselinePath == null || baselinePath.isBlank() ? null
                : AgentEvalReport.read(Path.of(baselinePath));
        assertThat(report.gate(corpus, baseline)).as("Evaluation gate; report: %s", reportFile).isEmpty();
    }

    private static AgentEvalReport report(int repetitions, List<AgentEvalScoring.Trial> trials) {
        return new AgentEvalReport(AgentEvalCorpus.VERSION, MODEL, PROVIDER,
                System.getenv().getOrDefault("KEX_EVAL_REVISION", "local-unrecorded"), repetitions, List.copyOf(trials));
    }
}
