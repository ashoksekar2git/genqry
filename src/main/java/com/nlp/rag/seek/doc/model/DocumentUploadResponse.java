package com.nlp.rag.seek.doc.model;

import java.time.Instant;
import java.util.List;

/**
 * Summary record returned to the frontend after a successful document upload + RAG init.
 */
public class DocumentUploadResponse {

    private String  docId;
    private String  fileName;
    private String  docType;
    private String  userName;
    private long    fileSizeBytes;
    private int     totalChunks;
    private int     embeddedChunks;
    private boolean embeddingActive;
    private String  savedPath;
    private Instant uploadedAt;
    private String  message;
    private List<String> errors;

    public DocumentUploadResponse() {}

    // ── getters / setters ────────────────────────────────────────────────────
    public String  getDocId()           { return docId; }
    public void    setDocId(String v)   { this.docId = v; }

    public String  getFileName()           { return fileName; }
    public void    setFileName(String v)   { this.fileName = v; }

    public String  getDocType()           { return docType; }
    public void    setDocType(String v)   { this.docType = v; }

    public String  getUserName()           { return userName; }
    public void    setUserName(String v)   { this.userName = v; }

    public long    getFileSizeBytes()           { return fileSizeBytes; }
    public void    setFileSizeBytes(long v)     { this.fileSizeBytes = v; }

    public int     getTotalChunks()           { return totalChunks; }
    public void    setTotalChunks(int v)      { this.totalChunks = v; }

    public int     getEmbeddedChunks()           { return embeddedChunks; }
    public void    setEmbeddedChunks(int v)      { this.embeddedChunks = v; }

    public boolean isEmbeddingActive()           { return embeddingActive; }
    public void    setEmbeddingActive(boolean v) { this.embeddingActive = v; }

    public String  getSavedPath()           { return savedPath; }
    public void    setSavedPath(String v)   { this.savedPath = v; }

    public Instant getUploadedAt()           { return uploadedAt; }
    public void    setUploadedAt(Instant v)  { this.uploadedAt = v; }

    public String  getMessage()           { return message; }
    public void    setMessage(String v)   { this.message = v; }

    public List<String> getErrors()          { return errors; }
    public void         setErrors(List<String> v) { this.errors = v; }
}

