package com.nlp.rag.seek.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * Global CORS configuration.
 *
 * Allowed origins are driven by the {@code seek.cors.allowed-origins} property,
 * which is set per Spring profile:
 *
 *   default (no profile) → * (all origins — suitable for local dev)
 *   dev  profile         → https://invinciblebots.dev
 *   prod profile         → https://invinciblebots.com
 *
 * Activate a profile at startup:
 *   java -jar seek.jar --spring.profiles.active=dev
 *   SPRING_PROFILES_ACTIVE=prod java -jar seek.jar
 */
@Configuration
public class CorsConfig {

    /**
     * Comma-separated list of allowed origins.
     * Defaults to "*" so local development works without any extra config.
     */
    @Value("${seek.cors.allowed-origins:*}")
    private String allowedOriginsRaw;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                List<String> origins = Arrays.stream(allowedOriginsRaw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();

                String[] originsArray = origins.contains("*")
                        ? new String[]{"*"}
                        : origins.toArray(new String[0]);

                registry.addMapping("/api/**")
                        .allowedOrigins(originsArray)
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        // credentials cannot be combined with wildcard origin
                        .allowCredentials(!origins.contains("*"))
                        .maxAge(3600);
            }
        };
    }
}

