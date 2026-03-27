package com.nlp.rag.seek.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for POST /api/auth/register
 *
 * {
 *   "username": "ashok",
 *   "email":    "ashok@example.com",
 *   "password": "mySecret123"
 * }
 */
@Schema(description = "New user registration payload")
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserRegistrationRequest {

    @Schema(description = "Unique username", example = "ashok")
    @NotBlank(message = "username is required")
    private String username;

    @Schema(description = "Email address", example = "ashok@example.com")
    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid email address")
    private String email;

    @Schema(description = "Password (min 6 chars)", example = "mySecret123")
    @NotBlank(message = "password is required")
    @Size(min = 6, message = "password must be at least 6 characters")
    private String password;

    public UserRegistrationRequest() {}

    public String getUsername() { return username; }
    public String getEmail()    { return email; }
    public String getPassword() { return password; }

    public void setUsername(String v) { this.username = v; }
    public void setEmail(String v)    { this.email = v; }
    public void setPassword(String v) { this.password = v; }
}
