package com.nlp.rag.seek.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Global CORS configuration — shared by both Spring MVC and Spring Security.
 *
 * Produces a {@link CorsConfigurationSource} bean that
 * {@code SecurityConfig.cors(Customizer.withDefaults())} picks up automatically.
 *
 * Allowed origins are driven by the {@code genqry.cors.allowed-origins} property:
 *   default (no profile) → * (all origins — suitable for local dev)
 *   secretsfree / prod   → https://genqry.com, https://www.genqry.com
 */
@Configuration
public class CorsConfig {

    @Value("${genqry.cors.allowed-origins:*}")
    private String allowedOriginsRaw;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(3600L);
        config.setAllowCredentials(true);

        if (origins.contains("*")) {
            // allowedOriginPatterns supports wildcard + credentials
            config.setAllowedOriginPatterns(List.of("*"));
        } else {
            config.setAllowedOrigins(origins);
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}

