package com.example.chatbot.controller;

import com.example.chatbot.dto.DocumentDtos.DocumentView;
import com.example.chatbot.rag.DocumentIngestionService;
import com.example.chatbot.security.AppUserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentIngestionService ingestionService;

    /** POST /api/documents - upload a .txt/.md/.pdf file to be embedded and made searchable via RAG. */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<DocumentView> upload(@AuthenticationPrincipal AppUserPrincipal principal,
                                                @RequestParam("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(ingestionService.ingest(principal.getUser(), file));
    }

    @GetMapping
    public ResponseEntity<List<DocumentView>> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        return ResponseEntity.ok(ingestionService.listDocuments(principal.getUser()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        ingestionService.deleteDocument(principal.getUser(), id);
        return ResponseEntity.noContent().build();
    }
}
