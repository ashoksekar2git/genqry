package com.nlp.rag.seek.doc.model;

import java.util.HashMap;
import java.util.Map;

/**
 * A single chunk produced from a user-uploaded document.
 *
 * For PDF files the chunk may carry structural metadata (sectionType, sectionTitle,
 * pageNumber) extracted by PdfStructuralExtractorService using PDFBox font analysis.
 * For all other formats the sliding-window chunker populates the word-offset fields.
 *
 * Carries both the raw text and a metadata map so retrieval results can
 * surface file-level context (fileName, docType, userName, chunkIndex, page)
 * directly to the LLM prompt.
 */
public class DocumentChunk {

    private String chunkId;       // "<docId>#<chunkIndex>"
    private String docId;         // stable ID derived from fileName
    private String fileName;
    private String docType;       // PDF, DOCX, TXT, CSV, PPTX, …
    private String userName;
    private String text;
    private int    chunkIndex;
    private int    wordStart;
    private int    wordEnd;
    private float[] embedding;

    // ── Structural metadata (populated for PDF/DOCX structural chunking) ────
    /** HEADING | PARAGRAPH | TABLE | LIST | UNKNOWN */
    private String sectionType  = "UNKNOWN";
    /** The heading text that introduces this chunk's section (if identified). */
    private String sectionTitle = "";
    /** 1-based page number where this chunk starts (0 = unknown). */
    private int    pageNumber   = 0;

    /** Structured metadata injected into the LLM prompt alongside chunk text. */
    private Map<String, String> metadata = new HashMap<>();

    public DocumentChunk() {}

    // ── getters ──────────────────────────────────────────────────────────────
    public String  getChunkId()      { return chunkId; }
    public String  getDocId()        { return docId; }
    public String  getFileName()     { return fileName; }
    public String  getDocType()      { return docType; }
    public String  getUserName()     { return userName; }
    public String  getText()         { return text; }
    public int     getChunkIndex()   { return chunkIndex; }
    public int     getWordStart()    { return wordStart; }
    public int     getWordEnd()      { return wordEnd; }
    public float[] getEmbedding()    { return embedding; }
    public String  getSectionType()  { return sectionType; }
    public String  getSectionTitle() { return sectionTitle; }
    public int     getPageNumber()   { return pageNumber; }
    public Map<String, String> getMetadata() { return metadata; }

    // ── setters ──────────────────────────────────────────────────────────────
    public void setChunkId(String v)      { this.chunkId = v; }
    public void setDocId(String v)        { this.docId = v; }
    public void setFileName(String v)     { this.fileName = v; }
    public void setDocType(String v)      { this.docType = v; }
    public void setUserName(String v)     { this.userName = v; }
    public void setText(String v)         { this.text = v; }
    public void setChunkIndex(int v)      { this.chunkIndex = v; }
    public void setWordStart(int v)       { this.wordStart = v; }
    public void setWordEnd(int v)         { this.wordEnd = v; }
    public void setEmbedding(float[] v)   { this.embedding = v; }
    public void setSectionType(String v)  { this.sectionType = v; }
    public void setSectionTitle(String v) { this.sectionTitle = v; }
    public void setPageNumber(int v)      { this.pageNumber = v; }
    public void setMetadata(Map<String, String> v) { this.metadata = v; }
}

