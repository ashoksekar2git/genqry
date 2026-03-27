package com.nlp.rag.seek.model;

/**
 * Represents a single detected PII/PHI value that was masked
 * before the query was sent to the LLM.
 *
 * The {@code placeholder} (e.g. {@code __PII_SSN_1__}) is substituted
 * into the sanitized query; the {@code originalValue} is restored into
 * the generated SQL after the LLM responds.
 */
public class PiiToken {

    /** PII category label — e.g. SSN, EMAIL, PHONE, NAME, DOB, ADDRESS, SECRET */
    private String category;

    /** The exact original sensitive value found in the user's query */
    private String originalValue;

    /** Placeholder token inserted in place of the sensitive value */
    private String placeholder;

    /** Zero-based character position in the ORIGINAL query where the value starts */
    private int startIndex;

    /** Zero-based character position (exclusive) where the value ends */
    private int endIndex;

    public PiiToken() {}

    public PiiToken(String category, String originalValue, String placeholder,
                    int startIndex, int endIndex) {
        this.category      = category;
        this.originalValue = originalValue;
        this.placeholder   = placeholder;
        this.startIndex    = startIndex;
        this.endIndex      = endIndex;
    }

    public String getCategory()      { return category; }
    public String getOriginalValue() { return originalValue; }
    public String getPlaceholder()   { return placeholder; }
    public int    getStartIndex()    { return startIndex; }
    public int    getEndIndex()      { return endIndex; }

    public void setCategory(String v)      { this.category = v; }
    public void setOriginalValue(String v) { this.originalValue = v; }
    public void setPlaceholder(String v)   { this.placeholder = v; }
    public void setStartIndex(int v)       { this.startIndex = v; }
    public void setEndIndex(int v)         { this.endIndex = v; }

    @Override
    public String toString() {
        return String.format("PiiToken{category='%s', placeholder='%s', original='%s', pos=[%d,%d)}",
                category, placeholder, mask(originalValue), startIndex, endIndex);
    }

    /** Returns a partially masked version of the value safe for logging */
    private static String mask(String v) {
        if (v == null || v.length() <= 2) return "***";
        return v.charAt(0) + "*".repeat(v.length() - 2) + v.charAt(v.length() - 1);
    }
}

