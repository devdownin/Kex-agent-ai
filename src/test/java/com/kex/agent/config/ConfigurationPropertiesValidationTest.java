// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.time.Duration;
import java.util.Map;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationPropertiesValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void refuse_un_debit_nul_qui_ferait_diviser_le_seau_par_zero() {
        assertThat(validator.validate(new RateLimitProperties(true, 0, 20)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("requestsPerMinute");
    }

    @Test
    void refuse_des_bornes_de_conversation_inexploitables() {
        AgentProperties properties = new AgentProperties("prompt", 0, -1, false, "", Map.of(), Map.of(), Map.of(),
                Duration.ZERO);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("maxHistoryMessages", "maxMessageCharacters", "requestTimeout");
    }
}
