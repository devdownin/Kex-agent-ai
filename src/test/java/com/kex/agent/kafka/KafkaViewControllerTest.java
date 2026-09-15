// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.kafka;

import java.util.List;

import com.kex.agent.supervision.Coverage;
import com.kex.agent.supervision.StopReason;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@WebMvcTest(KafkaViewController.class)
// Sécurité désactivée ici : elle a son propre test, ces cas visent le contrat HTTP.
@AutoConfigureMockMvc(addFilters = false)
class KafkaViewControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    KafkaViewService kafka;

    @Test
    void une_vue_indisponible_reste_un_200_qui_dit_pourquoi() {
        // Un serveur MCP absent est une information d'exploitation, pas une panne de l'agent :
        // rendre une erreur HTTP ferait tomber l'écran au lieu de l'informer.
        given(kafka.topics()).willReturn(KafkaTopics.unavailable("kafka-explorer injoignable"));

        assertThat(mvc.get().uri("/api/agent/kafka/topics"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.unavailable").isEqualTo("kafka-explorer injoignable");
    }

    @Test
    void une_mesure_absente_se_serialise_absente_et_non_a_zero() {
        // Le point que tout ce paquet protège : un 0 sérialisé se lirait « rattrapé ».
        given(kafka.topics()).willReturn(new KafkaTopics(
                List.of(new KafkaTopic("demo.orders", 6, MeasuredValue.unmeasured("partition 3 illisible"),
                        MeasuredValue.unmeasured("aucun message lu"), false)),
                Coverage.notReported(), List.of(), false, null));

        var response = mvc.get().uri("/api/agent/kafka/topics").assertThat().hasStatusOk().bodyJson();
        response.extractingPath("$.topics[0].records.measured").isEqualTo(false);
        response.extractingPath("$.topics[0].records.value").isNull();
        response.extractingPath("$.topics[0].records.reason").isEqualTo("partition 3 illisible");
    }

    @Test
    void rend_le_lag_d_un_topic_avec_son_verdict() {
        given(kafka.lag("demo.orders")).willReturn(new KafkaTopicLag("demo.orders",
                List.of(new KafkaGroupLag("reporting", "EMPTY", "CLASSIC",
                        new MeasuredValue(12_421L, true, null), new MeasuredValue(480_000L, true, null),
                        2, "STALLED", "aucun membre assigné", null)),
                1, 2, "STALLED",
                new Coverage(false, StopReason.TOPIC_LIMIT, List.of(), null), List.of(), false, null));

        var response = mvc.get().uri("/api/agent/kafka/topics/demo.orders/lag")
                .assertThat().hasStatusOk().bodyJson();
        response.extractingPath("$.worstVerdict").isEqualTo("STALLED");
        response.extractingPath("$.groups[0].verdict").isEqualTo("STALLED");
        response.extractingPath("$.groupsInCluster").isEqualTo(2);
    }
}
