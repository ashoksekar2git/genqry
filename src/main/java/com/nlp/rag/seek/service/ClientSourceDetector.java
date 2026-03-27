package com.nlp.rag.seek.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Detects whether an HTTP request originated from a browser UI or a
 * programmatic API client (curl, Postman, backend service, batch job, etc.).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Detection order (first match wins):
 *
 *  1. Explicit body field  – request.source = "UI" | "API" | "CURL" | "BATCH"
 *     The caller explicitly declares itself. Takes highest precedence.
 *
 *  2. X-Client-Source header  – same values as above.
 *     Useful for SPA/mobile clients that can set custom headers.
 *
 *  3. Accept header contains "text/html"
 *     Browser requests always include text/html in Accept.
 *     REST clients typically send only application/json.
 *
 *  4. Origin or Referer header present
 *     Browsers send Origin on cross-origin requests and Referer on navigation.
 *     curl / Postman / backend services usually do not send these.
 *
 *  5. X-Requested-With: XMLHttpRequest
 *     Set by jQuery, Angular HttpClient, and older AJAX frameworks.
 *     Indicates an in-browser AJAX call.
 *
 *  6. User-Agent analysis
 *     Browser User-Agents contain "Mozilla/", "Chrome/", "Safari/", "Firefox/".
 *     curl → "curl/x.y.z", Java HttpClient → "Java/", Postman → "PostmanRuntime/".
 *
 *  7. Default → API  (fail-safe: if nothing matches, treat as programmatic client)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Result values:
 *   UI    – browser / web frontend request  → format SQL with \n + indentation
 *   API   – REST client / service call      → compact single-line SQL
 *   CURL  – explicit curl identification
 *   BATCH – explicit batch/bulk identification
 */
@Component
public class ClientSourceDetector {

    private static final Logger log = LoggerFactory.getLogger(ClientSourceDetector.class);

    /** Header name for explicit client source declaration */
    public static final String HEADER_CLIENT_SOURCE = "X-Client-Source";

    public enum Source {
        UI, API, CURL, BATCH;

        /** True when SQL should be pretty-printed with newlines */
        public boolean isUi() { return this == UI; }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Determine the client source for the given request.
     *
     * @param httpRequest  the incoming HTTP request (never null)
     * @param explicitHint value from the JSON body's "source" field (may be null)
     * @return detected {@link Source}
     */
    public Source detect(HttpServletRequest httpRequest, String explicitHint) {

        // ── 1. Explicit body field ────────────────────────────────────────────
        if (explicitHint != null && !explicitHint.isBlank()) {
            Source s = parseSource(explicitHint);
            if (s != null) {
                log.debug("ClientSource: BODY hint '{}' → {}", explicitHint, s);
                return s;
            }
        }

        // ── 2. X-Client-Source header ─────────────────────────────────────────
        String headerHint = httpRequest.getHeader(HEADER_CLIENT_SOURCE);
        if (headerHint != null && !headerHint.isBlank()) {
            Source s = parseSource(headerHint);
            if (s != null) {
                log.debug("ClientSource: X-Client-Source '{}' → {}", headerHint, s);
                return s;
            }
        }

        // ── 3. Accept: text/html ──────────────────────────────────────────────
        String accept = emptyIfNull(httpRequest.getHeader("Accept")).toLowerCase();
        if (accept.contains("text/html")) {
            log.debug("ClientSource: Accept=text/html → UI");
            return Source.UI;
        }

        // ── 4. Origin or Referer present ──────────────────────────────────────
        String origin  = httpRequest.getHeader("Origin");
        String referer = httpRequest.getHeader("Referer");
        if ((origin  != null && !origin.isBlank()) ||
            (referer != null && !referer.isBlank())) {
            log.debug("ClientSource: Origin='{}' Referer='{}' → UI", origin, referer);
            return Source.UI;
        }

        // ── 5. X-Requested-With: XMLHttpRequest ───────────────────────────────
        String xrw = emptyIfNull(httpRequest.getHeader("X-Requested-With"));
        if ("XMLHttpRequest".equalsIgnoreCase(xrw)) {
            log.debug("ClientSource: X-Requested-With=XMLHttpRequest → UI");
            return Source.UI;
        }

        // ── 6. User-Agent analysis ────────────────────────────────────────────
        String ua = emptyIfNull(httpRequest.getHeader("User-Agent")).toLowerCase();
        if (!ua.isBlank()) {
            if (ua.startsWith("curl/")) {
                log.debug("ClientSource: User-Agent curl → CURL");
                return Source.CURL;
            }
            if (ua.contains("mozilla/") || ua.contains("chrome/") ||
                ua.contains("safari/")  || ua.contains("firefox/") ||
                ua.contains("webkit/")  || ua.contains("gecko/")) {
                log.debug("ClientSource: User-Agent browser → UI");
                return Source.UI;
            }
            // Java, Python, Postman, HTTPie, etc.
            if (ua.contains("java/") || ua.contains("python-requests") ||
                ua.contains("postmanruntime") || ua.contains("httpie") ||
                ua.contains("go-http") || ua.contains("axios")) {
                log.debug("ClientSource: User-Agent programmatic '{}' → API", ua);
                return Source.API;
            }
        }

        // ── 7. Default ────────────────────────────────────────────────────────
        log.debug("ClientSource: no signal matched → API (default)");
        return Source.API;
    }

    /**
     * Convenience: detect and return the string label ("UI" / "API" / "CURL" / "BATCH")
     */
    public String detectLabel(HttpServletRequest request, String explicitHint) {
        return detect(request, explicitHint).name();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Source parseSource(String value) {
        try {
            return Source.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null; // unknown value — fall through to next signal
        }
    }

    private String emptyIfNull(String s) {
        return s == null ? "" : s;
    }
}

