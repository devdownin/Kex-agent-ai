// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String BEARER = "bearer";

    @Bean
    OpenAPI kexAgentOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        return new OpenAPI()
                .info(new Info()
                        .title("Kex Agent AI")
                        .description("""
                                Agent IA outillé par MCP. Toutes les routes `/api/**` exigent \
                                `Authorization: Bearer <kex.agent.api-key>`.""")
                        .version(buildProperties.stream()
                                .map(BuildProperties::getVersion)
                                .findFirst()
                                .orElse("dev"))
                        .license(new License().name("GPL-3.0-or-later")
                                .url("https://www.gnu.org/licenses/gpl-3.0.html")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme(BEARER)
                        .description("La clé configurée dans kex.agent.api-key")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
