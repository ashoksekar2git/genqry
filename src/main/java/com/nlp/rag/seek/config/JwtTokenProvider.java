package com.nlp.rag.seek.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

/**
 * JWT token utility — generates, validates, and extracts claims from JSON Web Tokens.
 *
 * Token payload (claims):
 *   sub      = email
 *   username = username
 *   userType = Root | Admin | Registered | Temp | Passkey
 *   iat      = issued-at timestamp
 *   exp      = expiration timestamp
 *
 * <p>In <b>secretsfree mode</b>, the secret may be blank at startup. A temporary
 * random key is generated so the bean can be created. Call
 * {@link #reinitialize(String)} during bootstrap to replace it with the
 * real key. Any tokens issued with the temporary key will be invalid after
 * reinit (which is fine — no users can call protected endpoints before
 * bootstrap).</p>
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private volatile SecretKey signingKey;
    private final long expirationMs;

    public JwtTokenProvider(
            @Value("${seek.jwt.secret:}") String base64Secret,
            @Value("${seek.jwt.expiration-ms:86400000}") long expirationMs) {
        this.expirationMs = expirationMs;

        if (base64Secret != null && !base64Secret.isBlank()) {
            this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
            log.info("JwtTokenProvider initialised — expiration={}ms", expirationMs);
        } else {
            // Secretsfree mode: generate a temporary random key so the bean can be created
            byte[] tempKey = new byte[32];
            new SecureRandom().nextBytes(tempKey);
            this.signingKey = Keys.hmacShaKeyFor(tempKey);
            log.warn("JwtTokenProvider initialised with TEMPORARY key (secretsfree mode) — " +
                     "call reinitialize() during bootstrap to set the real key");
        }
    }

    /**
     * Re-initialise the signing key from a Base64-encoded secret.
     * Called by the bootstrap service after secrets are loaded.
     */
    public void reinitialize(String base64Secret) {
        this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
        log.info("JwtTokenProvider re-initialised with bootstrapped secret");
    }

    /**
     * Generates a signed JWT for the authenticated user.
     *
     * @param email    user's email (stored as subject)
     * @param username user's display username
     * @param userType user role/type (Root, Admin, Registered, Temp, Passkey)
     * @return compact JWT string
     */
    public String generateToken(String email, String username, String userType) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        String token = Jwts.builder()
                .subject(email)
                .claim("username", username)
                .claim("userType", userType)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();

        log.debug("JWT generated for email='{}' username='{}' userType='{}' expires={}",
                email, username, userType, expiry);
        return token;
    }

    /**
     * Validates the JWT signature and expiration.
     *
     * @param token the compact JWT string
     * @return true if valid and not expired
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT expired: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("JWT malformed: {}", e.getMessage());
        } catch (SecurityException e) {
            log.warn("JWT signature invalid: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT token is blank or null");
        }
        return false;
    }

    /**
     * Extracts the email (subject) from the token.
     */
    public String getEmailFromToken(String token) {
        return getClaims(token).getSubject();
    }

    /**
     * Extracts the username claim from the token.
     */
    public String getUsernameFromToken(String token) {
        return getClaims(token).get("username", String.class);
    }

    /**
     * Extracts the userType claim from the token.
     */
    public String getUserTypeFromToken(String token) {
        return getClaims(token).get("userType", String.class);
    }

    /**
     * Extracts the expiration date from the token.
     */
    public Date getExpirationFromToken(String token) {
        return getClaims(token).getExpiration();
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

