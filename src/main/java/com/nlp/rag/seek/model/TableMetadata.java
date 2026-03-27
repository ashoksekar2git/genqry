package com.nlp.rag.seek.model;

import java.util.List;

/**
 * Lightweight metadata view of a single database table.
 * Returned by the metadata API — contains only what the UI needs
 * to populate the schema browser and build NL query suggestions.
 *
 * Original table/column names are preserved exactly as they are in the DB
 * so the UI can display them correctly. Enriched fields (humanReadableName,
 * description) are included when available so the UI can show both the
 * abbreviated DB name and its human-readable meaning side-by-side.
 */
public class TableMetadata {

    private String              tableName;
    private String              humanReadableName;   // null when already descriptive
    private String              description;
    private String              businessContext;
    private int                 columnCount;
    private List<ColumnMetadata> columns;

    public TableMetadata() {}

    // ── getters ───────────────────────────────────────────────────────────────
    public String              getTableName()        { return tableName; }
    public String              getHumanReadableName(){ return humanReadableName; }
    public String              getDescription()      { return description; }
    public String              getBusinessContext()  { return businessContext; }
    public int                 getColumnCount()      { return columnCount; }
    public List<ColumnMetadata> getColumns()         { return columns; }

    // ── setters ───────────────────────────────────────────────────────────────
    public void setTableName(String v)               { this.tableName = v; }
    public void setHumanReadableName(String v)       { this.humanReadableName = v; }
    public void setDescription(String v)             { this.description = v; }
    public void setBusinessContext(String v)         { this.businessContext = v; }
    public void setColumnCount(int v)                { this.columnCount = v; }
    public void setColumns(List<ColumnMetadata> v)   { this.columns = v; }

    // ── builder ───────────────────────────────────────────────────────────────
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final TableMetadata t = new TableMetadata();
        public Builder tableName(String v)               { t.tableName = v;          return this; }
        public Builder humanReadableName(String v)       { t.humanReadableName = v;  return this; }
        public Builder description(String v)             { t.description = v;        return this; }
        public Builder businessContext(String v)         { t.businessContext = v;    return this; }
        public Builder columnCount(int v)                { t.columnCount = v;        return this; }
        public Builder columns(List<ColumnMetadata> v)   { t.columns = v;            return this; }
        public TableMetadata build()                     { return t; }
    }

    // =========================================================================
    // Nested: ColumnMetadata
    // =========================================================================

    /**
     * Lightweight metadata view of a single column within a table.
     */
    public static class ColumnMetadata {

        private String  columnName;
        private String  humanReadableName;   // null when already descriptive
        private String  dataType;
        private String  description;
        private boolean primaryKey;
        private boolean foreignKey;
        private String  foreignKeyReference;
        private boolean nullable;

        public ColumnMetadata() {}

        // ── getters ───────────────────────────────────────────────────────────
        public String  getColumnName()          { return columnName; }
        public String  getHumanReadableName()   { return humanReadableName; }
        public String  getDataType()            { return dataType; }
        public String  getDescription()         { return description; }
        public boolean isPrimaryKey()           { return primaryKey; }
        public boolean isForeignKey()           { return foreignKey; }
        public String  getForeignKeyReference() { return foreignKeyReference; }
        public boolean isNullable()             { return nullable; }

        // ── setters ───────────────────────────────────────────────────────────
        public void setColumnName(String v)          { this.columnName = v; }
        public void setHumanReadableName(String v)   { this.humanReadableName = v; }
        public void setDataType(String v)            { this.dataType = v; }
        public void setDescription(String v)         { this.description = v; }
        public void setPrimaryKey(boolean v)         { this.primaryKey = v; }
        public void setForeignKey(boolean v)         { this.foreignKey = v; }
        public void setForeignKeyReference(String v) { this.foreignKeyReference = v; }
        public void setNullable(boolean v)           { this.nullable = v; }

        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private final ColumnMetadata c = new ColumnMetadata();
            public Builder columnName(String v)          { c.columnName = v;          return this; }
            public Builder humanReadableName(String v)   { c.humanReadableName = v;   return this; }
            public Builder dataType(String v)            { c.dataType = v;            return this; }
            public Builder description(String v)         { c.description = v;         return this; }
            public Builder primaryKey(boolean v)         { c.primaryKey = v;          return this; }
            public Builder foreignKey(boolean v)         { c.foreignKey = v;          return this; }
            public Builder foreignKeyReference(String v) { c.foreignKeyReference = v; return this; }
            public Builder nullable(boolean v)           { c.nullable = v;            return this; }
            public ColumnMetadata build()                { return c; }
        }
    }
}

