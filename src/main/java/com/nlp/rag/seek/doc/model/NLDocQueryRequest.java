package com.nlp.rag.seek.doc.model;

/**
 * Request payload for POST /api/v1/seekDoc
 *
 * Example:
 * {
 *   "userName" : "ashok",
 *   "question" : "What is the refund policy?",
 *   "docId"    : "ashok_policy_guide",   // optional — search all docs if omitted
 *   "topK"     : 5                        // optional, default 5
 * }
 */
public class NLDocQueryRequest {

    private String  userName;
    private String  query;
    /** Raw filename sent by the frontend (e.g. "AshokW2.pdf"). Resolved to docId internally. */
    private String  documentName;
    /** Internal docId key (derived from userName + fileName). May be set directly. */
    private String  docId;
    private String  sessionId;
    private Integer topK;

    public NLDocQueryRequest() {}

    public String  getUserName()           { return userName; }
    public void    setUserName(String v)   { this.userName = v; }

    public String  getQuery()              { return query; }
    public void    setQuery(String v)      { this.query = v; }      // fixed typo: was setQuesry

    public String  getDocumentName()           { return documentName; }
    public void    setDocumentName(String v)   { this.documentName = v; }

    public String  getDocId()              { return docId; }
    public void    setDocId(String v)      { this.docId = v; }

    public String  getSessionId()          { return sessionId; }
    public void    setSessionId(String v)  { this.sessionId = v; }

    public Integer getTopK()               { return topK; }
    public void    setTopK(Integer v)      { this.topK = v; }
}
