package com.nlp.rag.seek.model;

import java.io.Serializable;
import java.util.List;

public class DatabaseTable implements Serializable {

    private String tableName;
    private String description;
    private String businessContext;
    private List<SchemaColumn> columns;

    /**
     * Human-readable expanded form of the table name — set by SchemaEnrichmentService.
     * e.g. "emp_dtls" → "employee details"
     * Null when the table name is already descriptive.
     */
    private String humanReadableName;

    /**
     * Semantic alias phrases for broader embedding coverage — set by SchemaEnrichmentService.
     * e.g. ["staff details", "worker details", "personnel details"]
     */
    private List<String> semanticAliases;

    // ── EAV / Vertical table support ─────────────────────────────────────────

    /**
     * True when this table uses the EAV (Entity–Attribute–Value) / vertical pattern.
     * Set by EavDetectionService or read from the schema JSON ("is_eav_table": true).
     */
    private boolean eavTable;

    /**
     * EAV structural configuration — non-null only when {@code eavTable == true}.
     */
    private EavConfig eavConfig;

    public DatabaseTable() {}

    public String getTableName()           { return tableName; }
    public String getDescription()         { return description; }
    public String getBusinessContext()     { return businessContext; }
    public List<SchemaColumn> getColumns() { return columns; }
    public String getHumanReadableName()   { return humanReadableName; }
    public List<String> getSemanticAliases() { return semanticAliases; }
    public boolean isEavTable()            { return eavTable; }
    public EavConfig getEavConfig()        { return eavConfig; }

    public void setTableName(String tableName)              { this.tableName = tableName; }
    public void setDescription(String description)          { this.description = description; }
    public void setBusinessContext(String businessContext)  { this.businessContext = businessContext; }
    public void setColumns(List<SchemaColumn> columns)      { this.columns = columns; }
    public void setHumanReadableName(String v)              { this.humanReadableName = v; }
    public void setSemanticAliases(List<String> v)          { this.semanticAliases = v; }
    public void setEavTable(boolean v)                      { this.eavTable = v; }
    public void setEavConfig(EavConfig v)                   { this.eavConfig = v; }

    /**
     * Rich semantic text used as the embedding chunk content.
     *
     * For DESCRIPTIVE tables ("employees"):
     *   "Table: employees. Description: ... Columns: ..."
     *
     * For ABBREVIATED tables ("emp_dtls"):
     *   "Table: emp_dtls. Expanded name: employee details.
     *    Aliases: staff details, worker details, personnel details.
     *    Description: Employee details — stores employee records.
     *    Columns: ..."
     *
     * The expanded name and aliases are the KEY addition — they place the
     * abbreviated table's vector close to the descriptive synonyms in
     * embedding space so semantic search finds it correctly.
     */
    public String toEmbeddingText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Table: ").append(tableName).append(". ");

        // ── Expanded name (core enrichment for abbreviated tables) ────────────
        if (humanReadableName != null && !humanReadableName.isBlank()
                && !humanReadableName.equalsIgnoreCase(tableName.replace("_", " "))) {
            sb.append("Expanded name: ").append(humanReadableName).append(". ");
        }

        // ── Semantic aliases ──────────────────────────────────────────────────
        if (semanticAliases != null && !semanticAliases.isEmpty()) {
            sb.append("Aliases: ").append(String.join(", ", semanticAliases)).append(". ");
        }

        if (description != null && !description.isBlank())
            sb.append("Description: ").append(description).append(". ");
        if (businessContext != null && !businessContext.isBlank())
            sb.append("Business context: ").append(businessContext).append(". ");

        // ── EAV / Vertical table warning ──────────────────────────────────────
        if (eavTable && eavConfig != null) {
            sb.append("WARNING: This is an EAV (Entity-Attribute-Value) table. ");
            sb.append("Do NOT use attribute names as column names in SQL. ");
            sb.append("Access pattern: WHERE ")
              .append(eavConfig.resolveAttributeNameColumn())
              .append(" = '<attribute_key>' to filter, and read ")
              .append(eavConfig.resolveAttributeValueColumn())
              .append(" for the value. ");
            sb.append("Entity linked via ").append(eavConfig.resolveEntityIdColumn());
            if (eavConfig.getEntityTable() != null && !eavConfig.getEntityTable().isBlank()) {
                sb.append(" referencing ").append(eavConfig.getEntityTable());
            }
            sb.append(". ");
            if (eavConfig.getKnownAttributes() != null && !eavConfig.getKnownAttributes().isEmpty()) {
                sb.append("Known attributes: ");
                for (EavKnownAttribute attr : eavConfig.getKnownAttributes()) {
                    sb.append(attr.getAttributeKey())
                      .append(" (").append(attr.getDataType() != null ? attr.getDataType() : "varchar").append(")");
                    if (attr.getSynonyms() != null && !attr.getSynonyms().isEmpty()) {
                        sb.append(" aka ").append(String.join("/", attr.getSynonyms()));
                    }
                    sb.append("; ");
                }
            }
        }

        if (columns != null && !columns.isEmpty()) {
            sb.append("Columns: ");
            for (SchemaColumn col : columns) {
                sb.append(col.getName()).append(" (").append(col.getDataType()).append(")");
                if (col.isPrimaryKey())  sb.append(" [PK]");
                if (col.isForeignKey())  sb.append(" [FK -> ").append(col.getForeignKeyReference()).append("]");
                sb.append("; ");
            }
            sb.append("Column descriptions: ");
            for (SchemaColumn col : columns) {
                if (col.getDescription() != null && !col.getDescription().isBlank())
                    sb.append(col.getName()).append(": ").append(col.getDescription()).append(". ");
            }
        }
        return sb.toString().trim();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final DatabaseTable t = new DatabaseTable();
        public Builder tableName(String v)           { t.tableName = v;        return this; }
        public Builder description(String v)         { t.description = v;      return this; }
        public Builder businessContext(String v)     { t.businessContext = v;  return this; }
        public Builder columns(List<SchemaColumn> v) { t.columns = v;          return this; }
        public Builder humanReadableName(String v)   { t.humanReadableName = v; return this; }
        public Builder semanticAliases(List<String> v){ t.semanticAliases = v; return this; }
        public Builder eavTable(boolean v)           { t.eavTable = v;         return this; }
        public Builder eavConfig(EavConfig v)        { t.eavConfig = v;        return this; }
        public DatabaseTable build()                 { return t; }
    }
}
