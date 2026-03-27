package com.nlp.rag.seek.doc.service;

import com.nlp.rag.seek.doc.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits extracted document text into overlapping word-level chunks using the
 * sliding-window (stride) strategy — separate from the SQL-schema chunker.
 *
 * Strategy
 * ────────
 *  chunkSize  : target number of words per chunk          (default 120)
 *  stride     : words to advance between consecutive chunks (default 60)
 *  overlap    : chunkSize – stride = words shared          (default 60)
 *
 * Each chunk carries metadata:
 *  doc_id, file_name, doc_type, user_name, chunk_index, word_start, word_end
 *
 * This metadata is stored in the document vector store and injected into the
 * LLM prompt so the model knows exactly which document and region was retrieved.
 */
@Service("documentSlidingWindowChunkingService")
public class DocumentSlidingWindowChunkingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSlidingWindowChunkingService.class);

    @Value("${seek.doc-rag.chunk-size:120}")
    private int chunkSize;

    @Value("${seek.doc-rag.stride:60}")
    private int stride;

    /**
     * Splits {@code text} into sliding-window chunks for the given document.
     *
     * @param docId    stable document ID (e.g. sanitised filename)
     * @param fileName original filename shown in metadata
     * @param docType  detected document type: PDF, DOCX, TXT, …
     * @param userName owner of the document
     * @param text     full plain text extracted from the document
     * @return ordered list of overlapping chunks ready for embedding
     */
    public List<DocumentChunk> chunk(String docId, String fileName,
                                     String docType, String userName,
                                     String text) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            log.warn("No text to chunk for document '{}'", fileName);
            return chunks;
        }

        String[] words = text.split("\\s+");
        int total  = words.length;
        int idx    = 0;

        for (int wordStart = 0; wordStart < total; wordStart += stride) {
            int wordEnd = Math.min(wordStart + chunkSize, total);

            // Build chunk text by rejoining the word slice
            StringBuilder sb = new StringBuilder();
            for (int w = wordStart; w < wordEnd; w++) {
                if (w > wordStart) sb.append(' ');
                sb.append(words[w]);
            }

            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocId(docId);
            chunk.setFileName(fileName);
            chunk.setDocType(docType);
            chunk.setUserName(userName);
            chunk.setChunkIndex(idx);
            chunk.setWordStart(wordStart);
            chunk.setWordEnd(wordEnd);
            chunk.setText(sb.toString());
            chunk.setChunkId(docId + "#" + idx);

            // Metadata for LLM prompt context
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("doc_id",      docId);
            meta.put("file_name",   fileName);
            meta.put("doc_type",    docType);
            meta.put("user_name",   userName);
            meta.put("chunk_index", String.valueOf(idx));
            meta.put("word_start",  String.valueOf(wordStart));
            meta.put("word_end",    String.valueOf(wordEnd));
            meta.put("element_type","DOC_CHUNK");
            chunk.setMetadata(meta);

            chunks.add(chunk);
            idx++;

            // If we've already consumed all words, stop
            if (wordEnd >= total) break;
        }

        log.info("Document '{}' ({}) → {} chunks (chunkSize={}, stride={}, overlap={})",
                fileName, docType, chunks.size(), chunkSize, stride, chunkSize - stride);
        return chunks;
    }
}

