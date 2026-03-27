package com.nlp.rag.seek.controller;

import com.nlp.rag.seek.model.SchemaChunk;
import com.nlp.rag.seek.service.VectorStoreService;
import com.nlp.rag.seek.service.VectorStoreService.ScoredChunk;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for inspecting the in-memory vector-store chunk index.
 *
 * Base path: /api/v1/admin/chunks
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  GET  /stats                   Index summary (counts, tables)    │
 * │  GET  /                        Paginated list of all chunks      │
 * │  GET  /{chunkId}               Full detail for one chunk         │
 * │  GET  /table/{tableName}       All chunks for a table            │
 * │  POST /search                  Semantic/keyword search           │
 * │  GET  /tables                  List all indexed table names      │
 * └──────────────────────────────────────────────────────────────────┘
 */
@RestController
@RequestMapping("/api/v1/admin/chunks")
@CrossOrigin(origins = "*", maxAge = 3600)
@Tag(name = "Chunk Admin", description = "Inspect the in-memory vector-store chunk index")
public class ChunkAdminController {

    @Autowired private VectorStoreService vectorStoreService;

    // =========================================================================
    // STATS
    // =========================================================================

    /**
     * GET /api/v1/admin/chunks/stats
     *
     * Returns a summary of the entire in-memory chunk index.
     *
     * Response example:
     * {
     *   "totalChunks": 146,
     *   "embeddingActive": false,
     *   "chunksWithVector": 0,
     *   "keywordOnly": 146,
     *   "chunksByTable": {
     *     "employees": 11,
     *     "departments": 7,
     *     "query_history": 21,
     *     ...
     *   },
     *   "chunksByType": { "TABLE": 15, "COLUMN": 131 }
     * }
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(vectorStoreService.indexStats());
    }

    // =========================================================================
    // LIST (paginated)
    // =========================================================================

    /**
     * GET /api/v1/admin/chunks
     *
     * Paginated list of indexed chunks with full metadata.
     * Raw float[] embedding vectors are excluded (too large for HTTP).
     * Use GET /{chunkId} to get hasEmbedding flag per chunk.
     *
     * Query params:
     *   table       — filter by table name, e.g. employees  (optional)
     *   elementType — TABLE | COLUMN  (optional, default = both)
     *   page        — 0-based page index  (default 0)
     *   size        — page size  (default 20, max 200)
     *
     * Response example:
     * {
     *   "total": 146, "page": 0, "pageSize": 20, "totalPages": 8,
     *   "chunks": [
     *     {
     *       "chunkId": "employees#0",
     *       "tableName": "employees",
     *       "columnName": null,
     *       "elementType": "TABLE",
     *       "text": "Table: employees Description: ...",
     *       "chunkIndex": 0,
     *       "wordStart": 0, "wordEnd": 69,
     *       "hasEmbedding": false,
     *       "embeddingDim": 0,
     *       "metadata": { "table_name": "employees", "primary_keys": "id", ... }
     *     }, ...
     *   ]
     * }
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listChunks(
            @RequestParam(required = false)                  String table,
            @RequestParam(required = false)                  String elementType,
            @RequestParam(defaultValue = "0")                int    page,
            @RequestParam(name = "size", defaultValue = "20") int   size) {

        return ResponseEntity.ok(
                vectorStoreService.listChunks(table, elementType, page, size));
    }

    // =========================================================================
    // GET ONE
    // =========================================================================

    /**
     * GET /api/v1/admin/chunks/{chunkId}
     *
     * Returns full detail for a single chunk by its chunkId.
     * ChunkId format: "<tableName>#<index>"  or  "<table>.<column>#<index>"
     *
     * Examples:
     *   GET /api/v1/admin/chunks/employees#0
     *   GET /api/v1/admin/chunks/employees.status#0
     *   GET /api/v1/admin/chunks/query_history#1
     *
     * Response includes text, metadata, position info, and embedding flag.
     * The actual float[] vector is NOT returned (use /search to see scores).
     */
    @GetMapping("/{chunkId}")
    public ResponseEntity<?> getChunk(@PathVariable String chunkId) {
        return vectorStoreService.getChunkById(chunkId)
                .map(c -> ResponseEntity.ok(vectorStoreService.toView(c)))
                .orElse(ResponseEntity.notFound().build());
    }

    // =========================================================================
    // BY TABLE
    // =========================================================================

    /**
     * GET /api/v1/admin/chunks/table/{tableName}
     *
     * Returns ALL chunks for a specific table — both TABLE-level and
     * all COLUMN-level chunks, sorted by chunkIndex.
     *
     * Example:
     *   GET /api/v1/admin/chunks/table/employees
     *
     * Response:
     * {
     *   "tableName": "employees",
     *   "totalChunks": 11,
     *   "tableChunks": 1,
     *   "columnChunks": 10,
     *   "chunks": [ { ... }, ... ]
     * }
     */
    @GetMapping("/table/{tableName}")
    public ResponseEntity<?> getByTable(@PathVariable String tableName) {
        List<SchemaChunk> chunks = vectorStoreService.getChunksByTable(tableName);
        if (chunks.isEmpty())
            return ResponseEntity.notFound().build();

        long tableCount  = chunks.stream().filter(c -> "TABLE".equals(c.getElementType())).count();
        long columnCount = chunks.stream().filter(c -> "COLUMN".equals(c.getElementType())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableName",    tableName);
        result.put("totalChunks",  chunks.size());
        result.put("tableChunks",  tableCount);
        result.put("columnChunks", columnCount);
        result.put("chunks", chunks.stream()
                .sorted(Comparator.comparingInt(SchemaChunk::getChunkIndex))
                .map(vectorStoreService::toView)
                .collect(Collectors.toList()));
        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // LIST ALL TABLE NAMES
    // =========================================================================

    /**
     * GET /api/v1/admin/chunks/tables
     *
     * Returns the distinct table names currently indexed,
     * with their chunk counts broken down by type.
     *
     * Response:
     * {
     *   "totalTables": 15,
     *   "tables": [
     *     { "tableName": "employees",   "totalChunks": 11, "tableChunks": 1, "columnChunks": 10 },
     *     { "tableName": "departments", "totalChunks": 7,  "tableChunks": 1, "columnChunks": 6  },
     *     ...
     *   ]
     * }
     */
    @GetMapping("/tables")
    public ResponseEntity<Map<String, Object>> listTables() {
        List<SchemaChunk> all = vectorStoreService.getAllChunks();

        // Group by table name
        Map<String, List<SchemaChunk>> byTable = all.stream()
                .filter(c -> c.getTableName() != null)
                .collect(Collectors.groupingBy(
                        SchemaChunk::getTableName,
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<Map<String, Object>> tables = byTable.entrySet().stream()
                .map(e -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("tableName",    e.getKey());
                    t.put("totalChunks",  e.getValue().size());
                    t.put("tableChunks",  e.getValue().stream()
                            .filter(c -> "TABLE".equals(c.getElementType())).count());
                    t.put("columnChunks", e.getValue().stream()
                            .filter(c -> "COLUMN".equals(c.getElementType())).count());
                    return t;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "totalTables", tables.size(),
                "tables",      tables
        ));
    }

    // =========================================================================
    // SEARCH
    // =========================================================================

    /**
     * POST /api/v1/admin/chunks/search
     *
     * Runs a semantic (or keyword-fallback) search against the chunk index
     * and returns scored results — exactly what the NL→SQL pipeline uses
     * internally when choosing context for the LLM.
     *
     * This endpoint lets you verify WHICH chunks would be retrieved for a
     * given query, and their similarity scores, without generating SQL.
     *
     * Request body:
     * { "query": "show active employees in engineering", "topK": 5 }
     *
     * Response:
     * {
     *   "query": "show active employees in engineering",
     *   "topK": 5,
     *   "mode": "KEYWORD_FALLBACK",
     *   "results": [
     *     {
     *       "rank": 1,
     *       "score": 0.600,
     *       "chunkId": "employees#0",
     *       "tableName": "employees",
     *       "elementType": "TABLE",
     *       "text": "Table: employees ...",
     *       "metadata": { ... }
     *     }, ...
     *   ]
     * }
     */
    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        if (query == null || query.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "'query' is required"));

        int topK = body.containsKey("topK")
                ? Integer.parseInt(body.get("topK").toString()) : 5;
        topK = Math.min(topK, 50);

        List<ScoredChunk> scored = vectorStoreService.search(query, topK);

        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            ScoredChunk sc = scored.get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rank",        i + 1);
            r.put("score",       Math.round(sc.score() * 10000.0) / 10000.0);
            r.put("chunkId",     sc.chunk().getChunkId());
            r.put("tableName",   sc.chunk().getTableName());
            r.put("columnName",  sc.chunk().getColumnName());
            r.put("elementType", sc.chunk().getElementType());
            r.put("text",        sc.chunk().getText());
            r.put("hasEmbedding",sc.chunk().getEmbedding() != null);
            r.put("metadata",    sc.chunk().getMetadata());
            results.add(r);
        }

        return ResponseEntity.ok(Map.of(
                "query",   query,
                "topK",    topK,
                "mode",    vectorStoreService.isEmbeddingActive() ? "SEMANTIC" : "KEYWORD_FALLBACK",
                "found",   results.size(),
                "results", results
        ));
    }
}
