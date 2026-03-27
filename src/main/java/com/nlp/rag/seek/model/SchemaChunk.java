package com.nlp.rag.seek.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A single text chunk produced by the chunking strategy.
 *
 * Chunking uses:
 *  - chunkSize : maximum number of words per chunk
 *  - stride    : number of words to advance before the next chunk
 *  - overlap   : chunkSize - stride  →  words shared between consecutive chunks
 *
 * Each chunk also carries a {@code metadata} map with structured fields
 * (tableName, columnName, elementType, dataType, isPrimaryKey, isForeignKey,
 *  foreignKeyReference, description) so that semantic search results can
 *  surface precise schema context to the LLM prompt.
 */
public class SchemaChunk {

    private String chunkId;
    private String tableName;
    private String columnName;
    private String elementType;      // TABLE | COLUMN
    private String text;             // rich semantic text sent to embedding model
    private int chunkIndex;
    private int wordStart;
    private int wordEnd;
    private float[] embedding;
    private List<String> tokens;

    /**
     * Structured metadata for this chunk — injected into the LLM prompt
     * alongside the chunk text so the model knows exactly which table/column
     * the text refers to.
     */
    private Map<String, String> metadata = new HashMap<>();

    public SchemaChunk() {}

    // ── getters ──────────────────────────────────────────────────────────────
    public String getChunkId()         { return chunkId; }
    public String getTableName()       { return tableName; }
    public String getColumnName()      { return columnName; }
    public String getElementType()     { return elementType; }
    public String getText()            { return text; }
    public int getChunkIndex()         { return chunkIndex; }
    public int getWordStart()          { return wordStart; }
    public int getWordEnd()            { return wordEnd; }
    public float[] getEmbedding()      { return embedding; }
    public List<String> getTokens()    { return tokens; }
    public Map<String, String> getMetadata() { return metadata; }

    // ── setters ──────────────────────────────────────────────────────────────
    public void setChunkId(String v)         { this.chunkId = v; }
    public void setTableName(String v)       { this.tableName = v; }
    public void setColumnName(String v)      { this.columnName = v; }
    public void setElementType(String v)     { this.elementType = v; }
    public void setText(String v)            { this.text = v; }
    public void setChunkIndex(int v)         { this.chunkIndex = v; }
    public void setWordStart(int v)          { this.wordStart = v; }
    public void setWordEnd(int v)            { this.wordEnd = v; }
    public void setEmbedding(float[] v)      { this.embedding = v; }
    public void setTokens(List<String> v)    { this.tokens = v; }
    public void setMetadata(Map<String, String> v) { this.metadata = v; }

    /** Convenience: add a single metadata entry. */
    public SchemaChunk withMeta(String key, String value) {
        if (value != null) this.metadata.put(key, value);
        return this;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final SchemaChunk c = new SchemaChunk();
        public Builder chunkId(String v)           { c.chunkId = v;      return this; }
        public Builder tableName(String v)         { c.tableName = v;    return this; }
        public Builder columnName(String v)        { c.columnName = v;   return this; }
        public Builder elementType(String v)       { c.elementType = v;  return this; }
        public Builder text(String v)              { c.text = v;         return this; }
        public Builder chunkIndex(int v)           { c.chunkIndex = v;   return this; }
        public Builder wordStart(int v)            { c.wordStart = v;    return this; }
        public Builder wordEnd(int v)              { c.wordEnd = v;      return this; }
        public Builder embedding(float[] v)        { c.embedding = v;    return this; }
        public Builder tokens(List<String> v)      { c.tokens = v;       return this; }
        public Builder metadata(Map<String,String> v) { c.metadata = v;  return this; }
        public SchemaChunk build()                 { return c; }
    }
}
