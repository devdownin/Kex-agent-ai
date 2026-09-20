// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
        Map<String, ApiKeyAuthFilter.Credential> credentialsByName = credentialsByName(properties);
        if (credentialsByName.isEmpty()) {
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
                        // La console est du HTML, du CSS et du JavaScript inertes : elle n'agit
                        // pas et ne porte aucun secret — le jeton est saisi dans le navigateur et
                        // n'existe que côté client. Même posture que Swagger UI ci-dessus, et même
                        // raison : l'authentifier la rendrait inutilisable sans rien protéger. Les
                        // chemins sont énumérés plutôt que laissés à un joker de racine, pour
                        // qu'une future route servie ici n'hérite pas de l'ouverture.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/agent/chat", "/api/agent/chat/structured",
                                "/api/agent/chat/stream").hasAnyRole("CHAT", "OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/agent/conversations/**")
                                .hasAnyRole("CHAT", "OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/agent/supervision/cycles",
                                "/api/agent/supervision/processes/*/maintenance",
                                "/api/agent/supervision/decisions/*/approve",
                                "/api/agent/supervision/decisions/*/reject",
                                "/api/agent/supervision/pause", "/api/agent/supervision/resume",
                                "/api/agent/supervision/notify/test").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/agent/supervision/processes/*/maintenance")
                                .hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/agent/mcp/servers/*/tools/*",
                                "/api/agent/knowledge", "/api/agent/skills/*/approve").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/agent/mcp/servers",
                                "/api/agent/mcp/servers/test", "/api/agent/mcp/servers/*/enabled",
                                "/api/agent/mcp/servers/*/refresh", "/api/agent/mcp/configuration")
                                .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/agent/mcp/servers/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/agent/mcp/servers/*/secret").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/agent/mcp/servers/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/agent/supervision/policy").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/agent/memory/*",
                                "/api/agent/memory/summaries/*", "/api/agent/knowledge")
                                .hasRole("ADMIN")
                        .requestMatchers("/api/agent/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new ApiKeyAuthFilter(credentialsByName),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new ApiKeyAuthenticationEntryPoint(!credentialsByName.isEmpty())));

        if (rateLimit.enabled()) {
            // Après l'autorisation : un appel non authentifié doit être refusé, pas consommer
            // le quota des appelants légitimes.
            http.addFilterAfter(new RateLimitFilter(rateLimit), AuthorizationFilter.class);
        }
        return http.build();
    }

    /**
     * {@code apiKey} reste le jeton anonyme historique ({@code kex-agent-api}) ; {@code apiKeys}
     * ajoute des jetons nommés qui deviennent chacun un principal distinct. Un ordre stable évite
     * qu'un doublon de nom entre les deux sources dépende de l'ordre d'itération d'une Map.
     */
    private static Map<String, ApiKeyAuthFilter.Credential> credentialsByName(AgentProperties properties) {
        Map<String, ApiKeyAuthFilter.Credential> credentials = new LinkedHashMap<>();
        if (StringUtils.hasText(properties.apiKey())) {
            credentials.put("kex-agent-api", credential(properties.apiKey(), ApiRole.ADMIN));
        }
        properties.apiKeys().forEach((name, token) -> {
            if (StringUtils.hasText(token)) {
                credentials.put(name, credential(token, properties.apiKeyRoles().getOrDefault(name, ApiRole.CHAT)));
            }
        });
        return credentials;
    }

    private static ApiKeyAuthFilter.Credential credential(String token, ApiRole role) {
        return new ApiKeyAuthFilter.Credential(token.getBytes(StandardCharsets.UTF_8), Set.of(role));
    }
}
