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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AgentProperties properties) throws Exception {
        if (!StringUtils.hasText(properties.apiKey())) {
            log.warn("kex.agent.api-key est vide : /api/** répondra 503. Définir KEX_AGENT_API_KEY.");
        }
        return http
                // API stateless sans cookie : ni session ni CSRF à protéger.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Sondes de conteneur : ouvertes, elles ne divulguent rien d'exploitable.
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new ApiKeyAuthFilter(properties.apiKey()),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new ApiKeyAuthenticationEntryPoint(properties.apiKey())))
                .build();
    }
}
