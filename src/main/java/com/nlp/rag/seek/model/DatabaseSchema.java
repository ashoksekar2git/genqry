package com.nlp.rag.seek.model;

import java.io.Serializable;
import java.util.List;

public class DatabaseSchema implements Serializable {

    private String databaseName;
    private String databaseType;
    private String description;
    private List<DatabaseTable> tables;

    public DatabaseSchema() {}

    public String getDatabaseName()       { return databaseName; }
    public String getDatabaseType()       { return databaseType; }
    public String getDescription()        { return description; }
    public List<DatabaseTable> getTables(){ return tables; }

    public void setDatabaseName(String v) { this.databaseName = v; }
    public void setDatabaseType(String v) { this.databaseType = v; }
    public void setDescription(String v)  { this.description = v; }
    public void setTables(List<DatabaseTable> v) { this.tables = v; }

    /**
     * Generate text representation for embedding
     */
    public String toEmbeddingText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Database: ").append(databaseName).append(" (").append(databaseType).append("). ");
        if (description != null && !description.isEmpty()) {
            sb.append("Description: ").append(description).append(". ");
        }
        if (tables != null && !tables.isEmpty()) {
            sb.append("Tables: ");
            for (DatabaseTable table : tables) {
                sb.append("[").append(table.toEmbeddingText()).append("] ");
            }
        }
        return sb.toString();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final DatabaseSchema s = new DatabaseSchema();
        public Builder databaseName(String v)        { s.databaseName = v; return this; }
        public Builder databaseType(String v)        { s.databaseType = v; return this; }
        public Builder description(String v)         { s.description = v;  return this; }
        public Builder tables(List<DatabaseTable> v) { s.tables = v;       return this; }
        public DatabaseSchema build()                { return s; }
    }
}
