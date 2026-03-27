package com.nlp.rag.seek.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT authentication filter — runs once per request.
 *
 * Reads the {@code Authorization: Bearer <token>} header, validates it via
 * {@link JwtTokenProvider}, and populates the Spring Security context so
 * downstream filters / controllers see an authenticated principal.
 *
 * If the header is missing or the token is invalid, the filter simply
 * continues without setting authentication — Spring Security will reject
 * the request if the endpoint requires auth.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(AUTH_HEADER);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();

            if (jwtTokenProvider.validateToken(token)) {
                String email    = jwtTokenProvider.getEmailFromToken(token);
                String username = jwtTokenProvider.getUsernameFromToken(token);
                String userType = jwtTokenProvider.getUserTypeFromToken(token);

                // Build authorities from userType
                List<SimpleGrantedAuthority> authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_USER"));
                if ("Root".equalsIgnoreCase(userType) || "Admin".equalsIgnoreCase(userType)) {
                    authorities = List.of(
                            new SimpleGrantedAuthority("ROLE_USER"),
                            new SimpleGrantedAuthority("ROLE_ADMIN"));
                }

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(email, null, authorities);
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("JWT authenticated: email='{}' username='{}' userType='{}'",
                        email, username, userType);
            }
        }

        filterChain.doFilter(request, response);
    }
}

