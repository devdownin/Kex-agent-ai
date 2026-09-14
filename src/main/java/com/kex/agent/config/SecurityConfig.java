// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AgentProperties properties,
                                            RateLimitProperties rateLimit) throws Exception {
        if (!StringUtils.hasText(properties.apiKey())) {
            log.warn("kex.agent.api-key est vide : /api/** répondra 503. Définir KEX_AGENT_API_KEY.");
        }
        http
                // CSRF levé uniquement sur /api/**, pas globalement : ces routes n'acceptent qu'un
                // bearer explicite, qu'un navigateur n'attache jamais de lui-même en cross-site —
                // il n'y a donc aucun credential ambiant à détourner. Partout ailleurs la
                // protection reste active, pour qu'une future route servie à un navigateur ne
                // l'hérite pas désactivée.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Sondes de conteneur : ouvertes, elles ne divulguent rien d'exploitable.
                        // /actuator/prometheus, lui, reste authentifié : il porte le modèle,
                        // le volume de jetons et les outils appelés.
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        // La *forme* de l'API est déjà publique : elle est dans le README et dans
                        // ce dépôt. Authentifier la spécification cacherait ce que personne ne
                        // cherche et rendrait Swagger UI inutilisable dans un navigateur. Ce qui
                        // est protégé, ce sont les routes qui agissent et qui coûtent.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new ApiKeyAuthFilter(properties.apiKey()),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new ApiKeyAuthenticationEntryPoint(properties.apiKey())));

        if (rateLimit.enabled()) {
            // Après l'autorisation : un appel non authentifié doit être refusé, pas consommer
            // le quota des appelants légitimes.
            http.addFilterAfter(new RateLimitFilter(rateLimit), AuthorizationFilter.class);
        }
        return http.build();
    }
}
