package com.nlp.rag.seek.model;

import java.io.Serializable;
import java.util.List;

public class SchemaColumn implements Serializable {

    private String name;
    private String dataType;
    private String description;
    private boolean primaryKey;
    private boolean foreignKey;
    private String foreignKeyReference;
    private boolean nullable;

    /**
     * Human-readable expanded form of the column name — set by SchemaEnrichmentService.
     * e.g. "f_nm" → "first name",  "sal" → "salary",  "dob" → "date of birth"
     */
    private String humanReadableName;

    /**
     * Semantic alias phrases — set by SchemaEnrichmentService.
     * e.g. salary → ["compensation", "pay", "wage", "earnings"]
     */
    private List<String> semanticAliases;

    public SchemaColumn() {}

    // ── getters ──────────────────────────────────────────────────────────────
    public String getName()                  { return name; }
    public String getDataType()              { return dataType; }
    public String getDescription()           { return description; }
    public boolean isPrimaryKey()            { return primaryKey; }
    public boolean isForeignKey()            { return foreignKey; }
    public String getForeignKeyReference()   { return foreignKeyReference; }
    public boolean isNullable()              { return nullable; }
    public String getHumanReadableName()     { return humanReadableName; }
    public List<String> getSemanticAliases() { return semanticAliases; }

    // ── setters ──────────────────────────────────────────────────────────────
    public void setName(String name)                                   { this.name = name; }
    public void setDataType(String dataType)                           { this.dataType = dataType; }
    public void setDescription(String description)                     { this.description = description; }
    public void setPrimaryKey(boolean primaryKey)                      { this.primaryKey = primaryKey; }
    public void setForeignKey(boolean foreignKey)                      { this.foreignKey = foreignKey; }
    public void setForeignKeyReference(String foreignKeyReference)     { this.foreignKeyReference = foreignKeyReference; }
    public void setNullable(boolean nullable)                          { this.nullable = nullable; }
    public void setHumanReadableName(String v)                         { this.humanReadableName = v; }
    public void setSemanticAliases(List<String> v)                     { this.semanticAliases = v; }

    /**
     * Rich semantic text for embedding.
     *
     * For DESCRIPTIVE columns ("first_name"):
     *   "Column: first_name. Data type: VARCHAR. Also known as: first name."
     *
     * For ABBREVIATED columns ("f_nm"):
     *   "Column: f_nm. Expanded name: first name.
     *    Aliases: given name, forename.
     *    Data type: VARCHAR. ..."
     */
    public String toEmbeddingText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Column: ").append(name).append(". ");

        // ── Expanded name (core enrichment for abbreviated columns) ───────────
        if (humanReadableName != null && !humanReadableName.isBlank()
                && !humanReadableName.equalsIgnoreCase(name.replace("_", " "))) {
            sb.append("Expanded name: ").append(humanReadableName).append(". ");
        }

        // ── Semantic aliases ──────────────────────────────────────────────────
        if (semanticAliases != null && !semanticAliases.isEmpty()) {
            sb.append("Aliases: ").append(String.join(", ", semanticAliases)).append(". ");
        }

        sb.append("Data type: ").append(dataType).append(". ");
        if (description != null && !description.isBlank())
            sb.append("Description: ").append(description).append(". ");
        if (primaryKey)
            sb.append("Role: primary key — uniquely identifies each row. ");
        if (foreignKey && foreignKeyReference != null)
            sb.append("Role: foreign key referencing ").append(foreignKeyReference).append(". ");
        sb.append("Nullable: ").append(nullable ? "yes" : "no").append(". ");

        // Readable snake_case / camelCase expansion (existing logic kept)
        String readableName = name.replace("_", " ").replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
        if (!readableName.equals(name.toLowerCase()))
            sb.append("Also known as: ").append(readableName).append(".");
        return sb.toString().trim();
    }

    // ── builder ──────────────────────────────────────────────────────────────
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final SchemaColumn col = new SchemaColumn();
        public Builder name(String v)                  { col.name = v;                  return this; }
        public Builder dataType(String v)              { col.dataType = v;              return this; }
        public Builder description(String v)           { col.description = v;           return this; }
        public Builder primaryKey(boolean v)           { col.primaryKey = v;            return this; }
        public Builder foreignKey(boolean v)           { col.foreignKey = v;            return this; }
        public Builder foreignKeyReference(String v)   { col.foreignKeyReference = v;   return this; }
        public Builder nullable(boolean v)             { col.nullable = v;              return this; }
        public Builder humanReadableName(String v)     { col.humanReadableName = v;     return this; }
        public Builder semanticAliases(List<String> v) { col.semanticAliases = v;       return this; }
        public SchemaColumn build()                    { return col; }
    }
}
