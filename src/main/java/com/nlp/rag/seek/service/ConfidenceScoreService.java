package com.nlp.rag.seek.service;

import com.nlp.rag.seek.service.VectorStoreService.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.StringJoiner;

/**
 * Computes a multi-factor confidence score (0–1) for a generated SQL query.
 *
 * Factors and weights
 * ───────────────────
 *  F1  semanticSimilarity   (0.40)  – average cosine score of top-k retrieved chunks
 *  F2  coverageRatio        (0.20)  – fraction of retrieved chunks that have score > threshold
 *  F3  sqlValidity          (0.20)  – 1.0 if SQL is valid, 0.0 otherwise
 *  F4  tableMatchRatio      (0.20)  – fraction of tables in generated SQL found in schema
 *
 * Final label  :  ≥ 0.75 → HIGH  |  ≥ 0.50 → MEDIUM  |  < 0.50 → LOW
 */
@Service
public class ConfidenceScoreService {

    private static final Logger log = LoggerFactory.getLogger(ConfidenceScoreService.class);

    private static final double W_SEMANTIC   = 0.40;
    private static final double W_COVERAGE   = 0.20;
    private static final double W_VALIDITY   = 0.20;
    private static final double W_TABLE      = 0.20;
    private static final double SCORE_THRESHOLD = 0.50;

    public record ConfidenceResult(double score, String label, String details) {}

    /**
     * @param scoredChunks  the retrieved chunks with cosine scores
     * @param sqlValid       whether the SQL passed structural validation
     * @param generatedSQL   the raw SQL string (used for table-name check)
     * @param relevantTables table names the RAG retrieved
     */
    public ConfidenceResult compute(List<ScoredChunk> scoredChunks,
                                    boolean sqlValid,
                                    String generatedSQL,
                                    List<String> relevantTables) {

        // F1 – average cosine similarity
        double f1 = scoredChunks.isEmpty() ? 0.0
                : scoredChunks.stream().mapToDouble(ScoredChunk::score).average().orElse(0.0);

        // F2 – coverage: % chunks above threshold
        long aboveThreshold = scoredChunks.stream()
                .filter(sc -> sc.score() >= SCORE_THRESHOLD)
                .count();
        double f2 = scoredChunks.isEmpty() ? 0.0
                : (double) aboveThreshold / scoredChunks.size();

        // F3 – SQL validity binary
        double f3 = sqlValid ? 1.0 : 0.0;

        // F4 – table match: how many retrieved tables appear in the generated SQL
        double f4 = 0.0;
        if (relevantTables != null && !relevantTables.isEmpty() && generatedSQL != null) {
            String sqlLower = generatedSQL.toLowerCase();
            long matched = relevantTables.stream()
                    .filter(t -> sqlLower.contains(t.toLowerCase()))
                    .count();
            f4 = (double) matched / relevantTables.size();
        }

        double score = W_SEMANTIC * f1
                     + W_COVERAGE * f2
                     + W_VALIDITY * f3
                     + W_TABLE    * f4;

        // clamp to [0, 1]
        score = Math.max(0.0, Math.min(1.0, score));

        String label = score >= 0.75 ? "HIGH" : score >= 0.50 ? "MEDIUM" : "LOW";

        String details = new StringJoiner(" | ")
                .add(String.format("semantic=%.3f(×%.2f)", f1, W_SEMANTIC))
                .add(String.format("coverage=%.3f(×%.2f)", f2, W_COVERAGE))
                .add(String.format("validity=%.1f(×%.2f)", f3, W_VALIDITY))
                .add(String.format("tableMatch=%.3f(×%.2f)", f4, W_TABLE))
                .add(String.format("→ total=%.3f [%s]", score, label))
                .toString();

        log.debug("Confidence: {}", details);
        return new ConfidenceResult(score, label, details);
    }
}

