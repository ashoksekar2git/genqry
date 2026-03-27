package com.nlp.rag.seek.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration — stateless JWT authentication.
 *
 * Public (no token required):
 *   /api/v1/auth/register
 *   /api/v1/auth/login
 *   /api/v1/auth/guest
 *   /api/v1/auth/verify
 *   /api/v1/auth/forgot-password
 *   /api/v1/auth/reset-password/**
 *   /api/v1/auth/passkey/**
 *
 * Protected (valid JWT required):
 *   All other /api/v1/** endpoints
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — stateless REST API with JWT
            .csrf(csrf -> csrf.disable())

            // Use the existing CorsConfig WebMvcConfigurer bean
            .cors(Customizer.withDefaults())

            // Stateless sessions — no HTTP session, no cookies
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Authorization rules
            .authorizeHttpRequests(auth -> auth
                // ── Swagger / OpenAPI (public) ───────────────────────────
                .requestMatchers(
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/v3/api-docs.yaml"
                ).permitAll()

                // ── Public auth endpoints (no JWT needed) ────────────────
                .requestMatchers(
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/guest",
                    "/api/v1/auth/verify",
                    "/api/v1/auth/forgot-password",
                    "/api/v1/auth/reset-password/**",
                    "/api/v1/auth/passkey/**",
                    "/api/v1/auth/token"
                ).permitAll()

                // ── All other API endpoints require authentication ───────
                .requestMatchers("/api/v1/**").authenticated()

                // ── Everything else (actuator, static, etc.) — permit ────
                .anyRequest().permitAll()
            )

            // Insert JWT filter before the default username/password filter
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

