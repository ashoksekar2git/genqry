package com.nlp.rag.seek.service;

/**
 * Shared utility for producing a consistent, filesystem-safe username/directory
 * name from any input string (including IP addresses).
 *
 * Rules:
 *  - Normalise IPv6 loopback (::1, 0:0:0:0:0:0:0:1) to 127.0.0.1 first.
 *  - Keep only alphanumeric characters (a-z, A-Z, 0-9).
 *  - Strip everything else (dots, colons, underscores, hyphens, spaces …).
 *
 * Examples:
 *   "127.0.0.1"        → "127001"
 *   "::1"              → "127001"
 *   "0:0:0:0:0:0:0:1"  → "127001"
 *   "192.168.10.5"     → "192168105"
 *   "AshokSekar"       → "AshokSekar"
 *   "ashok@example.com"→ "ashokexamplecom"
 */
public final class UsernameUtil {

    private UsernameUtil() {}

    /**
     * Strips every character that is not a-z, A-Z or 0-9.
     * Normalises IPv6 loopback to IPv4 127.0.0.1 first so all localhost
     * guest users share the same directory "127001".
     * Falls back to "unknown" if the result would be blank.
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";

        // Normalise IPv6 loopback variants to IPv4 loopback
        String normalized = raw.trim();
        if (isIpv6Loopback(normalized)) {
            normalized = "127.0.0.1";
        }

        String safe = normalized.replaceAll("[^a-zA-Z0-9]", "");
        return safe.isEmpty() ? "unknown" : safe;
    }

    /**
     * Returns true for IPv6 loopback representations:
     *   "::1", "0:0:0:0:0:0:0:1", "0000:0000:0000:0000:0000:0000:0000:0001"
     */
    private static boolean isIpv6Loopback(String addr) {
        if (addr == null) return false;
        String trimmed = addr.trim();
        // Strip leading/trailing brackets [::1]
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        // Remove all leading zeros in each group and compare
        String collapsed = trimmed.replaceAll("(^|:)0+", "$1").trim();
        return "::1".equals(collapsed)
            || "::1".equals(trimmed)
            || "0:0:0:0:0:0:0:1".equals(trimmed)
            || "0000:0000:0000:0000:0000:0000:0000:0001".equals(trimmed);
    }
}

