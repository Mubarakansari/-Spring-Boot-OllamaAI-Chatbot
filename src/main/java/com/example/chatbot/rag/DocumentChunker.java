package com.example.chatbot.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentChunker {

    /**
     * Extracts plain text from an uploaded file. Supports .txt/.md natively,
     * .pdf via PDFBox, and .xlsx/.xls via Apache POI (cell values, sheet by
     * sheet, tab-separated within a row so column structure survives chunking).
     */
    public String extractText(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();

        if (filename.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
                return new PDFTextStripper().getText(doc);
            }
        }
        if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
            return extractExcelText(file);
        }
        // .txt, .md, or anything else - treat as plain UTF-8 text
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    private String extractExcelText(MultipartFile file) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.getBytes()))) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder sb = new StringBuilder();
            for (Sheet sheet : workbook) {
                sb.append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell);
                        if (!value.isBlank()) {
                            sb.append(value).append('\t');
                        }
                    }
                    sb.append('\n');
                }
            }
            return sb.toString();
        }
    }

    /**
     * Sliding-window chunking by character count with overlap, so a fact that
     * happens to sit at a chunk boundary is still fully present in at least
     * one chunk. Character-based rather than token-based to keep this
     * dependency-free; it's an approximation, which is fine for chunk sizing
     * (exact token counts matter for the final prompt, not for chunking).
     */
    public List<String> chunk(String text, int chunkSizeChars, int overlapChars) {
        List<String> chunks = new ArrayList<>();
        String normalized = text.replaceAll("\\s+", " ").strip();
        if (normalized.isEmpty()) return chunks;

        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + chunkSizeChars, normalized.length());
            // Prefer to break on a sentence/word boundary near the end, not mid-word
            if (end < normalized.length()) {
                int lastSpace = normalized.lastIndexOf(' ', end);
                if (lastSpace > start) end = lastSpace;
            }
            chunks.add(normalized.substring(start, end).strip());
            if (end == normalized.length()) break;
            start = Math.max(end - overlapChars, start + 1); // always make forward progress
        }
        return chunks;
    }
}
