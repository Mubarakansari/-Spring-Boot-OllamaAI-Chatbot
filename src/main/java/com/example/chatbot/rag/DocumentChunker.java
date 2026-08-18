package com.example.chatbot.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentChunker {

    /** Extracts plain text from an uploaded file. Supports .txt/.md natively and .pdf via PDFBox. */
    public String extractText(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();

        if (filename.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
                return new PDFTextStripper().getText(doc);
            }
        }
        // .txt, .md, or anything else - treat as plain UTF-8 text
        return new String(file.getBytes(), StandardCharsets.UTF_8);
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
