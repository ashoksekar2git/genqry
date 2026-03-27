package com.nlp.rag.seek.doc.service;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts plain text from any document format supported by Apache Tika:
 *   PDF, DOCX, DOC, XLSX, XLS, PPTX, PPT, TXT, CSV, HTML, ODT, RTF, …
 *
 * Also detects the MIME/document type so downstream code can label the
 * chunk metadata with the correct docType (PDF, DOCX, etc.).
 *
 * Completely separate from the SQL-RAG pipeline — operates only on
 * user-uploaded files, never touches schema or DB objects.
 */
@Service
public class DocumentTextExtractorService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractorService.class);

    /** Tika's lightweight facade — used only for type detection. */
    private final Tika tika = new Tika();

    /**
     * Detects the document type (MIME type) of the file at {@code path}.
     * Returns a human-readable label such as "PDF", "DOCX", "TXT", "CSV", etc.
     */
    public String detectDocType(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            String mime = tika.detect(is, path.getFileName().toString());
            return mimeToLabel(mime);
        } catch (Exception e) {
            log.warn("Could not detect document type for '{}': {}", path.getFileName(), e.getMessage());
            return "UNKNOWN";
        }
    }

    /**
     * Extracts all plain text from the document at {@code path}.
     * Returns an empty string on failure (never throws).
     *
     * The write-limit is set to -1 (unlimited) so large documents are not
     * silently truncated.
     */
    public String extractText(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            // -1 = no write limit → read entire file
            BodyContentHandler handler  = new BodyContentHandler(-1);
            Metadata            meta    = new Metadata();
            meta.set(TikaCoreProperties.RESOURCE_NAME_KEY, path.getFileName().toString());
            AutoDetectParser    parser  = new AutoDetectParser();
            ParseContext        context = new ParseContext();
            parser.parse(is, handler, meta, context);
            String text = handler.toString().trim();
            log.info("Extracted {} characters from '{}'", text.length(), path.getFileName());
            return text;
        } catch (Exception e) {
            log.error("Text extraction failed for '{}': {}", path.getFileName(), e.getMessage());
            return "";
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private String mimeToLabel(String mime) {
        if (mime == null) return "UNKNOWN";
        return switch (mime) {
            case "application/pdf"                                                          -> "PDF";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX";
            case "application/msword"                                                       -> "DOC";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"       -> "XLSX";
            case "application/vnd.ms-excel"                                                 -> "XLS";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "PPTX";
            case "application/vnd.ms-powerpoint"                                            -> "PPT";
            case "text/plain"                                                               -> "TXT";
            case "text/csv"                                                                 -> "CSV";
            case "text/html"                                                                -> "HTML";
            case "application/vnd.oasis.opendocument.text"                                 -> "ODT";
            case "application/rtf", "text/rtf"                                             -> "RTF";
            default -> mime.contains("/") ? mime.substring(mime.indexOf('/') + 1).toUpperCase() : mime.toUpperCase();
        };
    }
}

