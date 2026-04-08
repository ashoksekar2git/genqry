package com.nlp.rag.seek.doc.service;

import com.nlp.rag.seek.doc.model.DocumentChunk;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Structural PDF extractor — intra-page region-aware grid-reconstruction strategy (v2).
 *
 * Instead of classifying an ENTIRE page as either TABLE or PARAGRAPH, this version
 * segments each page into ordered regions (HEADING, TABLE, PARAGRAPH) so that mixed
 * pages (heading + table + footnotes) are handled correctly.
 *
 * For table regions:
 *   - Multi-line column headers ("Total" / "Proceeds") are merged into single headers
 *   - Sub-tables are detected via vertical gap analysis (Short-term vs Long-term)
 *   - Row labels ("Short-term transactions for covered tax lots") are preserved as
 *     section titles for the sub-table chunks that follow them
 *   - Each sub-table is emitted as ONE atomic DocumentChunk (sectionType=TABLE)
 *     in clean Markdown pipe-table format
 *
 * For non-table regions:
 *   - Gap-aware text joining prevents garbled concatenation of numbers
 *   - Y-gaps insert line breaks; large X-gaps insert spacing
 *   - Heading detection uses font-size relative to page median
 */
@Service
public class PdfStructuralExtractorService {

    private static final Logger log = LoggerFactory.getLogger(PdfStructuralExtractorService.class);

    // ── Tuning constants ──────────────────────────────────────────────────────

    /** Max horizontal distance (pt) between runs considered the same column. */
    private static final float COL_SNAP = 20f;

    /** Max vertical distance (pt) between runs considered the same row-band. */
    private static final float ROW_SNAP = 8f;

    /** Y-gap (pt) between consecutive header lines that should be merged. */
    private static final float HEADER_LINE_GAP = 14f;

    /** A region needs at least this many distinct columns to be treated as a table. */
    private static final int MIN_TABLE_COLS = 3;

    /** A region needs at least this many distinct rows to be treated as a table. */
    private static final int MIN_TABLE_ROWS = 3;

    /** Font-size ratio above page median → HEADING. */
    private static final float HEADING_RATIO = 1.20f;

    /**
     * Y-gap multiplier for sub-table boundary detection.
     * A gap > SUB_TABLE_GAP_FACTOR × median-row-gap signals a new sub-table.
     */
    private static final float SUB_TABLE_GAP_FACTOR = 2.5f;

    /**
     * X-gap (pt) threshold: if the horizontal distance between the end of one run
     * and start of the next exceeds this, insert extra spacing in paragraph text.
     */
    private static final float COLUMN_GAP_THRESHOLD = 30f;

    @Value("${genqry.pdf.max-words-per-chunk:150}")
    private int maxWordsPerChunk;

    // =========================================================================
    // Public API
    // =========================================================================

    public List<DocumentChunk> extractChunks(Path pdfPath, String docId,
                                             String fileName, String userName) {
        log.info("PDF structural extraction (region-aware grid-reconstruction v2) for '{}'", fileName);
        List<DocumentChunk> result = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            int totalPages = doc.getNumberOfPages();
            FontAwarePDFStripper stripper = new FontAwarePDFStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(totalPages);
            stripper.writeText(doc, new StringWriter());

            List<TextRun> allRuns = stripper.getRuns();
            if (allRuns.isEmpty()) {
                log.warn("No text runs found in '{}' — structural extraction skipped", fileName);
                return result;
            }

            // Group runs by page
            Map<Integer, List<TextRun>> byPage = allRuns.stream()
                    .collect(Collectors.groupingBy(r -> r.pageNum,
                            LinkedHashMap::new, Collectors.toList()));

            int chunkIdx = 0;
            String currentHeading = "";

            for (Map.Entry<Integer, List<TextRun>> pageEntry : byPage.entrySet()) {
                int pageNum = pageEntry.getKey();
                List<TextRun> pageRuns = pageEntry.getValue();

                // Segment page into regions (HEADING, TABLE, PARAGRAPH)
                float pageMedianFontSize = median(
                        pageRuns.stream().map(r -> r.fontSize).collect(Collectors.toList()));
                List<PageRegion> regions = segmentPage(pageRuns, pageMedianFontSize);

                log.debug("Page {} segmented into {} regions: {}", pageNum, regions.size(),
                        regions.stream().map(r -> r.type.name()).collect(Collectors.joining(", ")));

                for (PageRegion region : regions) {
                    switch (region.type) {
                        case HEADING:
                            String headingText = region.runs.stream()
                                    .map(r -> r.text.trim())
                                    .collect(Collectors.joining(" "));
                            currentHeading = headingText;
                            result.add(makeChunk(docId, fileName, userName, chunkIdx++,
                                    headingText, "HEADING", headingText, pageNum));
                            break;

                        case TABLE:
                            PageGrid grid = buildGrid(region.runs);
                            if (grid.isTable()) {
                                List<TableResult> tables = grid.renderSubTables();
                                for (TableResult tr : tables) {
                                    String title = tr.sectionTitle != null && !tr.sectionTitle.isBlank()
                                            ? tr.sectionTitle : currentHeading;
                                    result.add(makeChunk(docId, fileName, userName, chunkIdx++,
                                            tr.markdown, "TABLE", title, pageNum));
                                }
                            } else {
                                // Region looked like a table but didn't pass grid threshold
                                String paraText = gapAwareJoin(region.runs);
                                if (!paraText.isBlank()) {
                                    for (String segment : splitByWordBudget(paraText)) {
                                        result.add(makeChunk(docId, fileName, userName, chunkIdx++,
                                                segment, "PARAGRAPH", currentHeading, pageNum));
                                    }
                                }
                            }
                            break;

                        case PARAGRAPH:
                            String paraText = gapAwareJoin(region.runs);
                            if (!paraText.isBlank()) {
                                for (String segment : splitByWordBudget(paraText)) {
                                    result.add(makeChunk(docId, fileName, userName, chunkIdx++,
                                            segment, "PARAGRAPH", currentHeading, pageNum));
                                }
                            }
                            break;
                    }
                }
            }

            log.info("Region-aware extraction complete for '{}': {} chunks across {} pages",
                    fileName, result.size(), byPage.size());
        } catch (IOException e) {
            log.error("PDF extraction failed for '{}': {}", fileName, e.getMessage(), e);
        }
        return result;
    }

    // =========================================================================
    // Page segmentation — detect HEADING / TABLE / PARAGRAPH regions
    // =========================================================================

    /**
     * Segments a page's runs into ordered regions. Walks runs top-to-bottom (by Y).
     *
     * Strategy:
     *   1. Sort runs by Y (top-to-bottom), break ties by X (left-to-right).
     *   2. Group runs into Y-bands (runs within ROW_SNAP of each other).
     *   3. For each Y-band, count distinct X-column anchors.
     *   4. Classify each band:
     *      - HEADING: single run with font-size >= median * HEADING_RATIO and > 2 chars
     *      - TABLE_CANDIDATE: band has runs spanning >= MIN_TABLE_COLS distinct X positions
     *      - PARAGRAPH: everything else
     *   5. Merge consecutive same-type bands into a single region.
     *   6. Validate TABLE regions: must have >= MIN_TABLE_ROWS bands; otherwise demote to PARAGRAPH.
     */
    private List<PageRegion> segmentPage(List<TextRun> pageRuns, float medianFontSize) {
        if (pageRuns.isEmpty()) return Collections.emptyList();

        // Sort by Y ascending, then X ascending
        List<TextRun> sorted = new ArrayList<>(pageRuns);
        sorted.sort(Comparator.comparing((TextRun r) -> r.y).thenComparing(r -> r.x));

        // Group into Y-bands
        List<YBand> bands = new ArrayList<>();
        List<TextRun> currentBandRuns = new ArrayList<>();
        currentBandRuns.add(sorted.get(0));
        float bandY = sorted.get(0).y;

        for (int i = 1; i < sorted.size(); i++) {
            TextRun run = sorted.get(i);
            if (Math.abs(run.y - bandY) <= ROW_SNAP) {
                currentBandRuns.add(run);
            } else {
                bands.add(new YBand(bandY, new ArrayList<>(currentBandRuns)));
                currentBandRuns.clear();
                currentBandRuns.add(run);
                bandY = run.y;
            }
        }
        if (!currentBandRuns.isEmpty()) {
            bands.add(new YBand(bandY, currentBandRuns));
        }

        // Classify each band
        for (YBand band : bands) {
            band.type = classifyBand(band, medianFontSize);
        }

        // Merge consecutive same-type bands into regions
        List<PageRegion> regions = new ArrayList<>();
        RegionType currentType = bands.get(0).type;
        List<TextRun> regionRuns = new ArrayList<>(bands.get(0).runs);

        for (int i = 1; i < bands.size(); i++) {
            YBand band = bands.get(i);
            if (band.type == currentType) {
                regionRuns.addAll(band.runs);
            } else {
                regions.add(new PageRegion(currentType, new ArrayList<>(regionRuns)));
                currentType = band.type;
                regionRuns.clear();
                regionRuns.addAll(band.runs);
            }
        }
        if (!regionRuns.isEmpty()) {
            regions.add(new PageRegion(currentType, regionRuns));
        }

        // Validate TABLE regions: must have >= MIN_TABLE_ROWS Y-bands
        List<PageRegion> validated = new ArrayList<>();
        for (PageRegion region : regions) {
            if (region.type == RegionType.TABLE) {
                List<Float> ys = region.runs.stream().map(r -> r.y).sorted()
                        .collect(Collectors.toList());
                int bandCount = cluster(ys, ROW_SNAP).size();
                if (bandCount < MIN_TABLE_ROWS) {
                    region.type = RegionType.PARAGRAPH;
                    log.debug("Demoted TABLE region to PARAGRAPH ({} rows < {})", bandCount, MIN_TABLE_ROWS);
                }
            }
            // Merge with previous if same type after demotion
            if (!validated.isEmpty() && validated.get(validated.size() - 1).type == region.type) {
                validated.get(validated.size() - 1).runs.addAll(region.runs);
            } else {
                validated.add(region);
            }
        }

        return validated;
    }

    /**
     * Classifies a single Y-band of runs.
     */
    private RegionType classifyBand(YBand band, float medianFontSize) {
        // HEADING: single meaningful run with large font, no digits
        if (band.runs.size() <= 2) {
            String combinedText = band.runs.stream().map(r -> r.text.trim())
                    .collect(Collectors.joining(" "));
            float maxFontSize = band.runs.stream().map(r -> r.fontSize)
                    .max(Float::compare).orElse(0f);
            if (maxFontSize >= medianFontSize * HEADING_RATIO
                    && combinedText.length() > 2
                    && !combinedText.matches(".*\\d.*")) {
                return RegionType.HEADING;
            }
        }

        // TABLE_CANDIDATE: runs span >= MIN_TABLE_COLS distinct X positions
        List<Float> xs = band.runs.stream().map(r -> r.x).sorted().collect(Collectors.toList());
        int distinctCols = cluster(xs, COL_SNAP).size();
        if (distinctCols >= MIN_TABLE_COLS) {
            return RegionType.TABLE;
        }

        // Check if this looks like a label row for a table
        // (few runs, text matches common table label patterns)
        if (band.runs.size() <= 3) {
            String text = band.runs.stream().map(r -> r.text.trim())
                    .collect(Collectors.joining(" "));
            if (text.matches("(?i).*(transaction|total|short.?term|long.?term|undetermined|covered|noncovered).*")) {
                return RegionType.TABLE;
            }
        }

        return RegionType.PARAGRAPH;
    }

    // =========================================================================
    // Gap-aware text joining for PARAGRAPH regions
    // =========================================================================

    /**
     * Joins runs from a PARAGRAPH region with gap-awareness.
     * - Y-gap > ROW_SNAP → insert newline
     * - X-gap > COLUMN_GAP_THRESHOLD → insert tab/space padding
     * - Otherwise single space
     *
     * This prevents the garbled concatenation like "0.00 -10,049.81313,368.69265,795.44"
     * that happened when runs were blindly joined with a single space.
     */
    private String gapAwareJoin(List<TextRun> runs) {
        if (runs.isEmpty()) return "";
        List<TextRun> sorted = new ArrayList<>(runs);
        sorted.sort(Comparator.comparing((TextRun r) -> r.y).thenComparing(r -> r.x));

        StringBuilder sb = new StringBuilder();
        sb.append(sorted.get(0).text.trim());
        float prevY = sorted.get(0).y;
        float prevEndX = sorted.get(0).x + estimateWidth(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            TextRun run = sorted.get(i);
            float yGap = Math.abs(run.y - prevY);
            float xGap = run.x - prevEndX;

            if (yGap > ROW_SNAP) {
                sb.append('\n');
            } else if (xGap > COLUMN_GAP_THRESHOLD) {
                sb.append("    ");  // tab-like spacing for columnar layout
            } else {
                sb.append(' ');
            }
            sb.append(run.text.trim());
            prevY = run.y;
            prevEndX = run.x + estimateWidth(run);
        }
        return sb.toString().trim();
    }

    /** Estimates the pixel width of a text run based on character count and font size. */
    private float estimateWidth(TextRun run) {
        return run.text.length() * run.fontSize * 0.5f;
    }

    /** Splits text into segments that fit within the word budget. */
    private List<String> splitByWordBudget(String text) {
        List<String> segments = new ArrayList<>();
        if (text == null || text.isBlank()) return segments;

        String[] words = text.trim().split("\\s+");
        if (words.length <= maxWordsPerChunk) {
            segments.add(text.trim());
            return segments;
        }

        StringBuilder buf = new StringBuilder();
        int count = 0;
        for (String w : words) {
            if (count > 0) buf.append(' ');
            buf.append(w);
            count++;
            if (count >= maxWordsPerChunk) {
                segments.add(buf.toString().trim());
                buf.setLength(0);
                count = 0;
            }
        }
        if (buf.length() > 0) {
            segments.add(buf.toString().trim());
        }
        return segments;
    }

    // =========================================================================
    // Grid builder
    // =========================================================================

    /** Analyses the text runs within a region and builds a column/row grid. */
    private PageGrid buildGrid(List<TextRun> runs) {
        List<Float> xs = runs.stream().map(r -> r.x).sorted().collect(Collectors.toList());
        List<Float> colAnchors = cluster(xs, COL_SNAP);

        List<Float> ys = runs.stream().map(r -> r.y).sorted().collect(Collectors.toList());
        List<Float> rowAnchors = cluster(ys, ROW_SNAP);

        int nRows = rowAnchors.size();
        int nCols = colAnchors.size();

        @SuppressWarnings("unchecked")
        List<String>[][] cells = new List[nRows][nCols];
        for (int r = 0; r < nRows; r++)
            for (int c = 0; c < nCols; c++)
                cells[r][c] = new ArrayList<>();

        // Slightly wider snap for cell placement to avoid orphan runs
        float cellColSnap = COL_SNAP * 1.5f;
        float cellRowSnap = ROW_SNAP * 1.5f;

        for (TextRun run : runs) {
            int col = closestIndex(colAnchors, run.x, cellColSnap);
            int row = closestIndex(rowAnchors, run.y, cellRowSnap);
            if (col >= 0 && row >= 0) {
                cells[row][col].add(run.text.trim());
            }
        }

        return new PageGrid(colAnchors, rowAnchors, cells, nRows, nCols);
    }

    // =========================================================================
    // PageGrid — detects tables and renders sub-tables
    // =========================================================================

    private class PageGrid {
        final List<Float>       colAnchors;
        final List<Float>       rowAnchors;
        final List<String>[][]  cells;
        final int               nRows;
        final int               nCols;

        PageGrid(List<Float> colAnchors, List<Float> rowAnchors,
                 List<String>[][] cells, int nRows, int nCols) {
            this.colAnchors = colAnchors;
            this.rowAnchors = rowAnchors;
            this.cells      = cells;
            this.nRows      = nRows;
            this.nCols      = nCols;
        }

        /** True if this region has enough columns, rows, and multi-column content. */
        boolean isTable() {
            if (nCols < MIN_TABLE_COLS || nRows < MIN_TABLE_ROWS) return false;
            int multiColRows = 0;
            for (int r = 0; r < nRows; r++) {
                int filled = 0;
                for (int c = 0; c < nCols; c++) {
                    if (!cells[r][c].isEmpty()) filled++;
                }
                if (filled >= 2) multiColRows++;
            }
            return multiColRows >= MIN_TABLE_ROWS;
        }

        /**
         * Renders the grid as one or more clean Markdown pipe sub-tables.
         *
         * Enhancements:
         *   1. Multi-line column header merging with HEADER_LINE_GAP check
         *   2. Sub-table splitting via vertical gap detection
         *   3. Row-label detection → section titles for sub-tables
         */
        List<TableResult> renderSubTables() {
            List<TableResult> results = new ArrayList<>();

            int firstDataRow = findFirstDataRow();
            String[] headers = mergeHeaders(firstDataRow);
            List<Integer> activeCols = findActiveCols(headers, firstDataRow);
            if (activeCols.isEmpty()) return results;

            // Split data rows into sub-table segments
            List<DataSegment> segments = splitDataIntoSegments(firstDataRow, activeCols);

            for (DataSegment seg : segments) {
                StringBuilder sb = new StringBuilder();

                // Section title prefix
                if (seg.sectionTitle != null && !seg.sectionTitle.isBlank()) {
                    sb.append("Section: ").append(seg.sectionTitle).append('\n');
                }

                // Header row
                sb.append("| ");
                for (int c : activeCols) {
                    sb.append(headers[c].isEmpty() ? "Column" : headers[c]).append(" | ");
                }
                sb.append('\n');

                // Separator
                sb.append("| ");
                for (int ignored : activeCols) sb.append("--- | ");
                sb.append('\n');

                // Data rows
                for (int r : seg.dataRows) {
                    sb.append("| ");
                    for (int c : activeCols) {
                        String cellText = String.join(" ", cells[r][c]).trim();
                        sb.append(cellText.isEmpty() ? "" : cellText).append(" | ");
                    }
                    sb.append('\n');
                }

                String markdown = sb.toString().trim();
                if (!markdown.isBlank() && !seg.dataRows.isEmpty()) {
                    results.add(new TableResult(markdown, seg.sectionTitle));
                }
            }

            // Fallback: single table if no segments produced
            if (results.isEmpty()) {
                String single = renderSingleTable(headers, activeCols, firstDataRow);
                if (!single.isBlank()) {
                    results.add(new TableResult(single, null));
                }
            }

            return results;
        }

        private int findFirstDataRow() {
            int firstDataRow = 0;
            for (int r = 0; r < nRows; r++) {
                boolean hasNumber = false;
                for (int c = 0; c < nCols; c++) {
                    String cell = String.join(" ", cells[r][c]).trim();
                    if (cell.matches(".*[0-9].*")) {
                        hasNumber = true;
                        break;
                    }
                }
                if (hasNumber) {
                    firstDataRow = r;
                    break;
                }
                firstDataRow = r + 1;
                // Stop merging headers if Y-gap to next row exceeds HEADER_LINE_GAP
                if (r + 1 < nRows) {
                    float yGap = Math.abs(rowAnchors.get(r + 1) - rowAnchors.get(r));
                    if (yGap > HEADER_LINE_GAP) {
                        firstDataRow = r + 1;
                        break;
                    }
                }
            }
            return firstDataRow;
        }

        private String[] mergeHeaders(int firstDataRow) {
            String[] headers = new String[nCols];
            for (int c = 0; c < nCols; c++) {
                StringBuilder hb = new StringBuilder();
                for (int r = 0; r < firstDataRow; r++) {
                    String token = String.join(" ", cells[r][c]).trim();
                    if (!token.isEmpty()) {
                        if (!hb.isEmpty()) hb.append(' ');
                        hb.append(token);
                    }
                }
                headers[c] = hb.isEmpty() ? "" : hb.toString();
            }
            return headers;
        }

        private List<Integer> findActiveCols(String[] headers, int firstDataRow) {
            List<Integer> activeCols = new ArrayList<>();
            for (int c = 0; c < nCols; c++) {
                boolean hasData = false;
                for (int r = firstDataRow; r < nRows; r++) {
                    if (!cells[r][c].isEmpty()) {
                        hasData = true;
                        break;
                    }
                }
                if (hasData || !headers[c].isEmpty()) activeCols.add(c);
            }
            return activeCols;
        }

        /**
         * Splits data rows into sub-table segments at:
         *   1. Label rows (only first column populated, non-numeric text)
         *   2. Large vertical gaps (> SUB_TABLE_GAP_FACTOR × median gap)
         */
        private List<DataSegment> splitDataIntoSegments(int firstDataRow, List<Integer> activeCols) {
            List<DataSegment> segments = new ArrayList<>();
            float medianGap = computeMedianRowGap(firstDataRow);

            String currentLabel = null;
            List<Integer> currentDataRows = new ArrayList<>();

            for (int r = firstDataRow; r < nRows; r++) {
                // Check if this is a label row
                if (isLabelRow(r, activeCols)) {
                    if (!currentDataRows.isEmpty()) {
                        segments.add(new DataSegment(currentLabel, new ArrayList<>(currentDataRows)));
                        currentDataRows.clear();
                    }
                    currentLabel = extractLabelText(r, activeCols);
                    continue;
                }

                // Check for vertical gap (sub-table boundary)
                if (!currentDataRows.isEmpty() && medianGap > 0) {
                    int prevRow = currentDataRows.get(currentDataRows.size() - 1);
                    float gap = Math.abs(rowAnchors.get(r) - rowAnchors.get(prevRow));
                    if (gap > SUB_TABLE_GAP_FACTOR * medianGap) {
                        segments.add(new DataSegment(currentLabel, new ArrayList<>(currentDataRows)));
                        currentDataRows.clear();
                        currentLabel = null;
                    }
                }

                // Add row if it has content
                boolean hasContent = false;
                for (int c : activeCols) {
                    if (!cells[r][c].isEmpty()) {
                        hasContent = true;
                        break;
                    }
                }
                if (hasContent) {
                    currentDataRows.add(r);
                }
            }

            if (!currentDataRows.isEmpty()) {
                segments.add(new DataSegment(currentLabel, currentDataRows));
            }

            return segments;
        }

        /** True if row has content only in the first active column and it's non-numeric. */
        private boolean isLabelRow(int r, List<Integer> activeCols) {
            if (activeCols.isEmpty()) return false;
            int filledCount = 0;
            int firstFilledCol = -1;
            StringBuilder allText = new StringBuilder();
            for (int c : activeCols) {
                String text = String.join(" ", cells[r][c]).trim();
                if (!text.isEmpty()) {
                    filledCount++;
                    if (firstFilledCol < 0) firstFilledCol = c;
                    allText.append(text).append(' ');
                }
            }
            if (filledCount == 1 && firstFilledCol == activeCols.get(0)) {
                String text = allText.toString().trim();
                return !text.matches(".*[0-9].*") && text.length() > 3;
            }
            return false;
        }

        private String extractLabelText(int r, List<Integer> activeCols) {
            for (int c : activeCols) {
                String text = String.join(" ", cells[r][c]).trim();
                if (!text.isBlank()) return text;
            }
            return null;
        }

        private float computeMedianRowGap(int firstDataRow) {
            List<Float> gaps = new ArrayList<>();
            for (int r = firstDataRow + 1; r < nRows; r++) {
                gaps.add(Math.abs(rowAnchors.get(r) - rowAnchors.get(r - 1)));
            }
            if (gaps.isEmpty()) return 0f;
            Collections.sort(gaps);
            return gaps.get(gaps.size() / 2);
        }

        private String renderSingleTable(String[] headers, List<Integer> activeCols, int firstDataRow) {
            StringBuilder sb = new StringBuilder();
            sb.append("| ");
            for (int c : activeCols) {
                sb.append(headers[c].isEmpty() ? "Column" : headers[c]).append(" | ");
            }
            sb.append('\n');
            sb.append("| ");
            for (int ignored : activeCols) sb.append("--- | ");
            sb.append('\n');
            for (int r = firstDataRow; r < nRows; r++) {
                boolean hasContent = false;
                for (int c : activeCols) {
                    if (!cells[r][c].isEmpty()) { hasContent = true; break; }
                }
                if (!hasContent) continue;
                sb.append("| ");
                for (int c : activeCols) {
                    String cellText = String.join(" ", cells[r][c]).trim();
                    sb.append(cellText.isEmpty() ? "" : cellText).append(" | ");
                }
                sb.append('\n');
            }
            return sb.toString().trim();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Clusters a sorted list of floats: consecutive values within {@code snap} are merged. */
    private List<Float> cluster(List<Float> sorted, float snap) {
        List<Float> anchors = new ArrayList<>();
        if (sorted.isEmpty()) return anchors;
        float prev  = sorted.get(0);
        int   count = 1;
        float sum   = prev;
        for (int i = 1; i < sorted.size(); i++) {
            float v = sorted.get(i);
            if (v - prev <= snap) {
                sum += v;
                count++;
            } else {
                anchors.add(sum / count);
                sum = v;
                count = 1;
            }
            prev = v;
        }
        anchors.add(sum / count);
        return anchors;
    }

    /**
     * Returns the index of the anchor closest to {@code value} within {@code snap},
     * or -1 if no anchor is within range.
     */
    private int closestIndex(List<Float> anchors, float value, float snap) {
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < anchors.size(); i++) {
            float d = Math.abs(anchors.get(i) - value);
            if (d <= snap && d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private float median(List<Float> values) {
        if (values.isEmpty()) return 10f;
        List<Float> s = new ArrayList<>(values);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    private DocumentChunk makeChunk(String docId, String fileName, String userName,
                                    int idx, String text, String sectionType,
                                    String sectionTitle, int pageNum) {
        DocumentChunk c = new DocumentChunk();
        c.setDocId(docId);
        c.setFileName(fileName);
        c.setDocType("PDF");
        c.setUserName(userName);
        c.setChunkIndex(idx);
        c.setChunkId(docId + "#" + idx);
        c.setText(text);
        c.setSectionType(sectionType);
        c.setSectionTitle(sectionTitle != null ? sectionTitle : "");
        c.setPageNumber(pageNum);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("doc_id",        docId);
        meta.put("file_name",     fileName);
        meta.put("doc_type",      "PDF");
        meta.put("user_name",     userName);
        meta.put("chunk_index",   String.valueOf(idx));
        meta.put("section_type",  sectionType);
        meta.put("section_title", sectionTitle != null ? sectionTitle : "");
        meta.put("page_number",   String.valueOf(pageNum));
        meta.put("element_type",  "PDF_STRUCTURAL_CHUNK");
        c.setMetadata(meta);
        return c;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    private enum RegionType { HEADING, TABLE, PARAGRAPH }

    private static class TextRun {
        String text;
        float  fontSize;
        float  x;
        float  y;
        int    pageNum;

        TextRun(String text, float fontSize, float x, float y, int pageNum) {
            this.text     = text;
            this.fontSize = fontSize;
            this.x        = x;
            this.y        = y;
            this.pageNum  = pageNum;
        }
    }

    /** A horizontal band of text runs at approximately the same Y coordinate. */
    private static class YBand {
        float y;
        List<TextRun> runs;
        RegionType type;

        YBand(float y, List<TextRun> runs) {
            this.y    = y;
            this.runs = runs;
        }
    }

    /** A contiguous region of the page with a single classification. */
    private static class PageRegion {
        RegionType type;
        List<TextRun> runs;

        PageRegion(RegionType type, List<TextRun> runs) {
            this.type = type;
            this.runs = runs;
        }
    }

    /** Result of rendering a sub-table: Markdown text + optional section title. */
    private static class TableResult {
        final String markdown;
        final String sectionTitle;

        TableResult(String markdown, String sectionTitle) {
            this.markdown     = markdown;
            this.sectionTitle = sectionTitle;
        }
    }

    /** A segment of data rows within a table, optionally preceded by a label row. */
    private static class DataSegment {
        final String sectionTitle;
        final List<Integer> dataRows;

        DataSegment(String sectionTitle, List<Integer> dataRows) {
            this.sectionTitle = sectionTitle;
            this.dataRows     = dataRows;
        }
    }

    private static class FontAwarePDFStripper extends PDFTextStripper {
        private final List<TextRun> runs = new ArrayList<>();
        private int currentPage = 0;

        FontAwarePDFStripper() throws IOException { super(); }

        @Override
        public void startPage(PDPage page) throws IOException {
            currentPage++;
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (text == null || text.isBlank() || positions == null || positions.isEmpty()) {
                super.writeString(text, positions);
                return;
            }
            TextPosition first = positions.get(0);
            String trimmed = text.trim();
            if (!trimmed.isEmpty()) {
                runs.add(new TextRun(
                        trimmed,
                        first.getFontSizeInPt(),
                        first.getXDirAdj(),
                        first.getYDirAdj(),
                        currentPage));
            }
            super.writeString(text, positions);
        }

        List<TextRun> getRuns() { return runs; }
    }
}

