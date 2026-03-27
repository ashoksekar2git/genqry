package com.nlp.rag.seek.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for POST /api/v1/auth/forgot-password
 *
 * {
 *   "email": "user@example.com"
 * }
 */
@Schema(description = "Forgot password request")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ForgotPasswordRequest {
    @Schema(description = "Registered email address", example = "user@example.com")
    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid email address")
    private String email;

    public ForgotPasswordRequest() {}

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}

