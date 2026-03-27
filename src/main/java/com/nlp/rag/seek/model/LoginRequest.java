package com.nlp.rag.seek.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for POST /api/v1/auth/login
 *
 * {
 *   "email":    "mailashoky@gmail.com",
 *   "password": "mySecret123"
 * }
 */
@Schema(description = "Login credentials")
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginRequest {

    @Schema(description = "User email address", example = "mailashoky@gmail.com")
    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid email address")
    private String email;

    @Schema(description = "User password", example = "mySecret123")
    @NotBlank(message = "password is required")
    private String password;

    public LoginRequest() {}

    public String getEmail()    { return email; }
    public String getPassword() { return password; }

    public void setEmail(String v)    { this.email = v; }
    public void setPassword(String v) { this.password = v; }
}
