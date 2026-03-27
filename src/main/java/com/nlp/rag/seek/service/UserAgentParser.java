package com.nlp.rag.seek.service;

/**
 * Lightweight User-Agent parser — no external library needed.
 * Extracts device type, OS, and browser name from the UA string.
 */
public class UserAgentParser {

    public record ParsedUA(String deviceType, String os, String browser) {}

    public static ParsedUA parse(String ua) {
        if (ua == null || ua.isBlank()) return new ParsedUA("Unknown", "Unknown", "Unknown");
        String u = ua.toLowerCase();

        // Device type
        String device;
        if (u.contains("mobile") || u.contains("android") && u.contains("mobile")) {
            device = "Mobile";
        } else if (u.contains("tablet") || u.contains("ipad")) {
            device = "Tablet";
        } else {
            device = "Desktop";
        }

        // OS
        String os;
        if (u.contains("windows nt"))        os = "Windows";
        else if (u.contains("mac os x"))      os = "macOS";
        else if (u.contains("iphone") || u.contains("ipad")) os = "iOS";
        else if (u.contains("android"))       os = "Android";
        else if (u.contains("linux"))         os = "Linux";
        else if (u.contains("chromeos"))      os = "ChromeOS";
        else                                   os = "Unknown";

        // Browser
        String browser;
        if (u.contains("edg/") || u.contains("edge/"))    browser = "Edge";
        else if (u.contains("opr/") || u.contains("opera")) browser = "Opera";
        else if (u.contains("chrome/") && !u.contains("chromium")) browser = "Chrome";
        else if (u.contains("chromium/"))     browser = "Chromium";
        else if (u.contains("firefox/"))      browser = "Firefox";
        else if (u.contains("safari/") && !u.contains("chrome")) browser = "Safari";
        else if (u.contains("curl/"))         browser = "cURL";
        else if (u.contains("postmanruntime")) browser = "Postman";
        else if (u.contains("java/"))         browser = "Java HTTP";
        else                                   browser = "Unknown";

        return new ParsedUA(device, os, browser);
    }
}

