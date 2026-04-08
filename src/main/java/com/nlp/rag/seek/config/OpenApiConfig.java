package com.nlp.rag.seek.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger-UI configuration for the genQry NL2SQL application.
 *
 * Swagger UI   : http://localhost:9095/swagger-ui.html
 * OpenAPI JSON  : http://localhost:9095/v3/api-docs
 *
 * All authenticated endpoints expect a JWT Bearer token.
 * Public endpoints (auth) do not require the token.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI genQryOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("genQry — NL2SQL RAG API")
                        .description(
                                "Natural Language to SQL conversion pipeline powered by RAG (Retrieval-Augmented Generation).\n\n" +
                                "**Authentication**: Most endpoints require a JWT Bearer token. " +
                                "Obtain one via `POST /api/v1/auth/login` or `POST /api/v1/auth/passkey/login/finish` " +
                                "and paste it into the **Authorize** dialog (lock icon above).\n\n" +
                                "**Public endpoints** (no token needed): `/api/v1/auth/*`")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("genQry Team")
                                .email("ragdataseek@gmail.com"))
                        .license(new License()
                                .name("Private")
                                .url("https://genqry.com")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Enter the JWT token obtained from /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .tags(List.of(
                        new Tag().name("Auth").description("User registration, login, password reset, email verification"),
                        new Tag().name("Passkey").description("WebAuthn / Passkey registration and authentication"),
                        new Tag().name("NL2SQL").description("Natural Language to SQL conversion pipeline"),
                        new Tag().name("SQL Execution").description("Execute validated SQL against the live datasource"),
                        new Tag().name("Documents").description("Document upload, query (Document RAG), and re-index"),
                        new Tag().name("Document Q&A").description("Natural language document question answering"),
                        new Tag().name("Business Rules").description("CRUD for LLM prompt injection rules"),
                        new Tag().name("Schema Metadata").description("Browse database schema tables and columns"),
                        new Tag().name("Database Admin").description("Database connection, SQL file upload, schema export"),
                        new Tag().name("Chunk Admin").description("Inspect the in-memory vector-store chunk index"),
                        new Tag().name("Semantics").description("Semantic alias groups for table/column name expansion"),
                        new Tag().name("Cache Admin").description("Semantic query cache inspection and management"),
                        new Tag().name("EAV").description("EAV table detection and attribute configuration"),
                        new Tag().name("Query History").description("User transcript / query history"),
                        new Tag().name("Workspace").description("User workspace file listing"),
                        new Tag().name("User Admin").description("User management (admin only)"),
                        new Tag().name("LLM Prompt").description("Direct LLM prompt invocation from file"),
                        new Tag().name("SQL Schema").description("SQL schema file upload and management")
                ));
    }
}

