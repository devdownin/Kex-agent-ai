// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.List;

import com.kex.agent.supervision.SupervisionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@WebMvcTest(MemoryController.class)
// Sécurité désactivée ici : elle a son propre test, ces cas visent le contrat HTTP.
@AutoConfigureMockMvc(addFilters = false)
class MemoryControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    MemoryService memory;

    @MockitoBean
    SupervisionService supervision;

    @Test
    void liste_les_souvenirs_actifs() {
        given(memory.list()).willReturn(List.of(
                new MemoryView("m1", "fait", "conv-1", Instant.parse("2026-09-15T05:00:00Z"))));

        assertThat(mvc.get().uri("/api/agent/memory"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[0].content").isEqualTo("fait");
    }

    @Test
    void supprime_un_souvenir_et_l_audite() {
        given(memory.forget("m1")).willReturn(
                new MemoryEntry("m1", "fait supprimé", "conv-1", Instant.parse("2026-09-15T05:00:00Z"), null));

        assertThat(mvc.delete().uri("/api/agent/memory/m1")).hasStatus(HttpStatus.NO_CONTENT);

        // La sécurité est désactivée dans ce test de contrat HTTP (addFilters = false) : le
        // principal n'est donc jamais résolu ici, seul le nom d'acteur par défaut est vérifiable.
        // L'extraction du nom réel est le même mécanisme que SupervisionController.actor(...).
        verify(supervision).auditAction("Anonyme", "Souvenir supprimé", "fait supprimé");
    }

    @Test
    void rend_404_pour_un_identifiant_inconnu_sans_rien_auditer() {
        willThrow(new UnknownMemoryException("inconnu")).given(memory).forget("inconnu");

        assertThat(mvc.delete().uri("/api/agent/memory/inconnu")).hasStatus(HttpStatus.NOT_FOUND);
        verifyNoInteractions(supervision);
    }
}
