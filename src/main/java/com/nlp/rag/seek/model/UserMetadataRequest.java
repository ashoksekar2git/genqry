package com.nlp.rag.seek.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Request payload for POST /api/v1/admin/metadata/add
 *
 * Example body:
 * {
 *   "databaseName": "ecommerce",
 *   "tableMetadata": {
 *     "tableName":   "employees",
 *     "description": "This table stores employee personal details...",
 *     "isEavTable":  true
 *   },
 *   "columnMetadata": [
 *     { "columnName": "id",         "description": "employee id primary key" },
 *     { "columnName": "first_name", "description": "employee first name" },
 *     ...
 *   ]
 * }
 *
 * Saved to: src/main/resources/metadata/{tableName}_metadata.json
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserMetadataRequest {

    private String              databaseName;
    private TableInfo           tableMetadata;
    private List<ColumnInfo>    columnMetadata;

    public UserMetadataRequest() {}

    public String           getDatabaseName()   { return databaseName; }
    public TableInfo        getTableMetadata()  { return tableMetadata; }
    public List<ColumnInfo> getColumnMetadata() { return columnMetadata; }

    public void setDatabaseName(String v)            { this.databaseName = v; }
    public void setTableMetadata(TableInfo v)         { this.tableMetadata = v; }
    public void setColumnMetadata(List<ColumnInfo> v) { this.columnMetadata = v; }

    // ── Nested: table-level info ──────────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TableInfo {
        private String tableName;
        private String description;
        private String businessContext;
        private Boolean isEavTable;

        public TableInfo() {}

        public String getTableName()      { return tableName; }
        public String getDescription()    { return description; }
        public String getBusinessContext(){ return businessContext; }
        public Boolean getIsEavTable()    { return isEavTable; }

        public void setTableName(String v)       { this.tableName = v; }
        public void setDescription(String v)     { this.description = v; }
        public void setBusinessContext(String v) { this.businessContext = v; }
        public void setIsEavTable(Boolean v)     { this.isEavTable = v; }
    }

    // ── Nested: column-level info ─────────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColumnInfo {
        private String columnName;
        private String description;

        public ColumnInfo() {}

        public String getColumnName()  { return columnName; }
        public String getDescription() { return description; }

        public void setColumnName(String v)  { this.columnName = v; }
        public void setDescription(String v) { this.description = v; }
    }
}
