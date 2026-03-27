package com.nlp.rag.seek.model;

import java.io.Serializable;
import java.util.List;

/**
 * Configuration block for an EAV (Entity–Attribute–Value) / Vertical table.
 *
 * Stored on a {@link DatabaseTable} when the table is identified as EAV.
 * Describes the structural columns of the EAV pattern and catalogs all
 * known logical attributes so ChunkingService and SQLGenerationService
 * can produce correct chunks, context, and SQL queries.
 *
 * JSON representation inside {DBName}_Schema_1.0.0.json:
 * <pre>
 * "is_eav_table": true,
 * "eav_config": {
 *   "entity_id_column":        "employee_id",
 *   "attribute_name_column":   "attribute_key",
 *   "attribute_value_column":  "attribute_value",
 *   "value_type_column":       "data_type",
 *   "entity_table":            "employees",
 *   "known_attributes": [
 *     { "attribute_key": "salary",    "data_type": "numeric", "description": "...", "synonyms": [...] },
 *     { "attribute_key": "hire_date", "data_type": "date",    "description": "...", "synonyms": [...] }
 *   ]
 * }
 * </pre>
 */
public class EavConfig implements Serializable {

    /** Column that links the EAV row back to the parent entity (e.g. "employee_id") */
    private String entityIdColumn;

    /** Column that holds the attribute name / key (e.g. "attribute_key", "property_name") */
    private String attributeNameColumn;

    /** Column that holds the attribute value (e.g. "attribute_value", "val") */
    private String attributeValueColumn;

    /**
     * Optional column that stores the data type of each row's value
     * (e.g. "data_type" containing "numeric", "date", "varchar").
     * Used to auto-generate CAST hints.
     */
    private String valueTypeColumn;

    /**
     * The parent entity table referenced by entityIdColumn.
     * e.g. "employees" — used to build JOIN hints in chunk text and prompts.
     */
    private String entityTable;

    /**
     * When the EAV data table does NOT have an attribute name column itself
     * (e.g. productattributevalues has attributeid FK but no name column),
     * the attribute names live in a separate meta table (e.g. "attributes").
     * This field stores that meta table name so JOIN hints can be generated.
     */
    private String attributeMetaTable;

    /**
     * The FK column in the EAV data table that joins to the attribute meta table.
     * e.g. "attributeid" → attributes.attributeid
     */
    private String attributeMetaJoinColumn;

    /**
     * The column in the attribute meta table that holds the attribute name.
     * e.g. "name" in attributes table.
     */
    private String attributeMetaNameColumn;

    /**
     * Catalog of all known logical attributes stored in this EAV table.
     * Each entry describes one distinct value of the attributeNameColumn.
     */
    private List<EavKnownAttribute> knownAttributes;

    public EavConfig() {}

    // ── getters / setters ─────────────────────────────────────────────────────

    public String getEntityIdColumn()           { return entityIdColumn; }
    public String getAttributeNameColumn()      { return attributeNameColumn; }
    public String getAttributeValueColumn()     { return attributeValueColumn; }
    public String getValueTypeColumn()          { return valueTypeColumn; }
    public String getEntityTable()              { return entityTable; }
    public String getAttributeMetaTable()       { return attributeMetaTable; }
    public String getAttributeMetaJoinColumn()  { return attributeMetaJoinColumn; }
    public String getAttributeMetaNameColumn()  { return attributeMetaNameColumn; }
    public List<EavKnownAttribute> getKnownAttributes() { return knownAttributes; }

    public void setEntityIdColumn(String v)     { this.entityIdColumn = v; }
    public void setAttributeNameColumn(String v){ this.attributeNameColumn = v; }
    public void setAttributeValueColumn(String v){ this.attributeValueColumn = v; }
    public void setValueTypeColumn(String v)    { this.valueTypeColumn = v; }
    public void setEntityTable(String v)        { this.entityTable = v; }
    public void setAttributeMetaTable(String v)       { this.attributeMetaTable = v; }
    public void setAttributeMetaJoinColumn(String v)  { this.attributeMetaJoinColumn = v; }
    public void setAttributeMetaNameColumn(String v)  { this.attributeMetaNameColumn = v; }
    public void setKnownAttributes(List<EavKnownAttribute> v) { this.knownAttributes = v; }

    /** Returns attribute name column, defaulting to "attribute_name" if not set. */
    public String resolveAttributeNameColumn() {
        return attributeNameColumn != null && !attributeNameColumn.isBlank()
                ? attributeNameColumn : "attribute_name";
    }

    /** Returns attribute value column, defaulting to "attribute_value" if not set. */
    public String resolveAttributeValueColumn() {
        return attributeValueColumn != null && !attributeValueColumn.isBlank()
                ? attributeValueColumn : "attribute_value";
    }

    /** Returns entity id column, defaulting to "entity_id" if not set. */
    public String resolveEntityIdColumn() {
        return entityIdColumn != null && !entityIdColumn.isBlank()
                ? entityIdColumn : "entity_id";
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final EavConfig c = new EavConfig();
        public Builder entityIdColumn(String v)          { c.entityIdColumn = v;          return this; }
        public Builder attributeNameColumn(String v)     { c.attributeNameColumn = v;     return this; }
        public Builder attributeValueColumn(String v)    { c.attributeValueColumn = v;    return this; }
        public Builder valueTypeColumn(String v)         { c.valueTypeColumn = v;          return this; }
        public Builder entityTable(String v)             { c.entityTable = v;              return this; }
        public Builder attributeMetaTable(String v)       { c.attributeMetaTable = v;       return this; }
        public Builder attributeMetaJoinColumn(String v)  { c.attributeMetaJoinColumn = v;  return this; }
        public Builder attributeMetaNameColumn(String v)  { c.attributeMetaNameColumn = v;  return this; }
        public Builder knownAttributes(List<EavKnownAttribute> v) { c.knownAttributes = v; return this; }
        public EavConfig build()                         { return c; }
    }
}

