package com.nlp.rag.seek.model;

import java.io.Serializable;
import java.util.List;

/**
 * Represents one logical attribute stored as a row in an EAV table.
 *
 * For example, in an employee_attributes EAV table:
 *   attribute_key  = "salary"
 *   dataType       = "numeric"
 *   castHint       = "CAST(attribute_value AS numeric)"
 *   description    = "Employee monthly salary"
 *   synonyms       = ["compensation", "pay", "wage", "earnings"]
 */
public class EavKnownAttribute implements Serializable {

    /** The value stored in the attribute_name / attribute_key column */
    private String attributeKey;

    /** SQL data type of this attribute's value (e.g. "numeric", "date", "varchar") */
    private String dataType;

    /** SQL CAST expression hint to use in generated queries */
    private String castHint;

    /** Human-readable description of this attribute */
    private String description;

    /**
     * Business synonyms — words users might say that map to this attribute.
     * e.g. salary → ["compensation", "pay", "wage", "earnings", "income"]
     */
    private List<String> synonyms;

    public EavKnownAttribute() {}

    // ── getters / setters ─────────────────────────────────────────────────────

    public String getAttributeKey()        { return attributeKey; }
    public String getDataType()            { return dataType; }
    public String getCastHint()            { return castHint; }
    public String getDescription()         { return description; }
    public List<String> getSynonyms()      { return synonyms; }

    public void setAttributeKey(String v)  { this.attributeKey = v; }
    public void setDataType(String v)      { this.dataType = v; }
    public void setCastHint(String v)      { this.castHint = v; }
    public void setDescription(String v)   { this.description = v; }
    public void setSynonyms(List<String> v){ this.synonyms = v; }

    /**
     * Derives a CAST hint automatically when none is explicitly set.
     * e.g. dataType="numeric" → "CAST(attribute_value AS numeric)"
     */
    public String resolveCastHint(String valueColumnName) {
        if (castHint != null && !castHint.isBlank()) return castHint;
        if (dataType == null || dataType.isBlank()) return valueColumnName;
        String dt = dataType.toLowerCase();
        // varchar / text / character varying → no cast needed, return column as-is
        if (dt.contains("varchar") || dt.contains("text") || dt.contains("char")) {
            return valueColumnName;
        }
        return "CAST(" + valueColumnName + " AS " + dataType + ")";
    }

    /**
     * Rich semantic embedding text for this logical attribute.
     * Used when creating EAV_ATTRIBUTE chunks in ChunkingService.
     */
    public String toEmbeddingText(String tableName, String entityIdColumn,
                                   String attributeNameColumn, String attributeValueColumn,
                                   String entityTable) {
        StringBuilder sb = new StringBuilder();
        sb.append("Logical attribute '").append(attributeKey).append("'");
        sb.append(" stored in EAV table '").append(tableName).append("'. ");

        if (description != null && !description.isBlank()) {
            sb.append("Description: ").append(description).append(". ");
        }

        // Synonyms — key for broad embedding coverage
        if (synonyms != null && !synonyms.isEmpty()) {
            sb.append("Also known as: ").append(String.join(", ", synonyms)).append(". ");
        }

        sb.append("To query this attribute: ");
        sb.append("SELECT ").append(resolveCastHint(attributeValueColumn));
        sb.append(" FROM ").append(tableName);
        sb.append(" WHERE ").append(attributeNameColumn).append(" = '").append(attributeKey).append("'");
        sb.append(". ");

        sb.append("Data type: ").append(dataType != null ? dataType : "varchar").append(". ");
        sb.append("Cast hint: ").append(resolveCastHint(attributeValueColumn)).append(". ");
        sb.append("Filter pattern: WHERE ").append(attributeNameColumn)
          .append(" = '").append(attributeKey).append("'. ");

        if (entityTable != null && !entityTable.isBlank()) {
            sb.append("Join to entity table: ").append(entityIdColumn)
              .append(" references ").append(entityTable).append(". ");
        }

        sb.append("IMPORTANT: never use '").append(attributeKey)
          .append("' as a column name — it is a VALUE in the '")
          .append(attributeNameColumn).append("' column. ");

        return sb.toString().trim();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final EavKnownAttribute a = new EavKnownAttribute();
        public Builder attributeKey(String v)  { a.attributeKey = v; return this; }
        public Builder dataType(String v)       { a.dataType = v;     return this; }
        public Builder castHint(String v)       { a.castHint = v;     return this; }
        public Builder description(String v)    { a.description = v;  return this; }
        public Builder synonyms(List<String> v) { a.synonyms = v;     return this; }
        public EavKnownAttribute build()        { return a; }
    }
}

