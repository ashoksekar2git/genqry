package com.nlp.rag.seek.doc.model;

import java.util.List;

/**
 * Response returned to the frontend for a document-grounded query.
 */
public class DocumentQueryResponse {

    private String       answer;
    private String       userName;
    private String       docId;
    private String       fileName;
    private double       confidenceScore;
    private String       confidenceLabel;   // HIGH | MEDIUM | LOW
    private boolean      fromCache;
    private List<String> sourceChunks;      // excerpt texts used as context
    private String       error;
    /** DB primary key of the transcript_details row — used by frontend to POST feedback. -1 if not persisted. */
    private int          transcriptId = -1;
    /** True when the LLM answer was detected as a hallucination (not grounded in excerpts). */
    private boolean      hallucinated;

    public DocumentQueryResponse() {}

    // ── getters / setters ────────────────────────────────────────────────────
    public String  getAnswer()           { return answer; }
    public void    setAnswer(String v)   { this.answer = v; }

    public String  getUserName()           { return userName; }
    public void    setUserName(String v)   { this.userName = v; }

    public String  getDocId()           { return docId; }
    public void    setDocId(String v)   { this.docId = v; }

    public String  getFileName()           { return fileName; }
    public void    setFileName(String v)   { this.fileName = v; }

    public double  getConfidenceScore()         { return confidenceScore; }
    public void    setConfidenceScore(double v) { this.confidenceScore = v; }

    public String  getConfidenceLabel()           { return confidenceLabel; }
    public void    setConfidenceLabel(String v)   { this.confidenceLabel = v; }

    public boolean isFromCache()          { return fromCache; }
    public void    setFromCache(boolean v){ this.fromCache = v; }

    public List<String> getSourceChunks()               { return sourceChunks; }
    public void         setSourceChunks(List<String> v) { this.sourceChunks = v; }

    public String  getError()           { return error; }
    public void    setError(String v)   { this.error = v; }

    public int     getTranscriptId()       { return transcriptId; }
    public void    setTranscriptId(int v)  { this.transcriptId = v; }

    public boolean isHallucinated()           { return hallucinated; }
    public void    setHallucinated(boolean v) { this.hallucinated = v; }
}
