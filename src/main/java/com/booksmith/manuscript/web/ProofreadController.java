package com.booksmith.manuscript.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.manuscript.internal.ProofreadService;
import com.booksmith.manuscript.internal.ProofreadService.BlockIssues;
import com.booksmith.manuscript.internal.ProofreadService.BlockText;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Corretor ortográfico e gramatical próprio (§8.5, etapa 2) e o dicionário do livro. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class ProofreadController {

    private final ProofreadService proofreadService;
    private final CurrentUser currentUser;

    /** Os parágrafos que mudaram ({@code id} do bloco e texto); devolve os erros de cada um. */
    @PostMapping("/chapters/{chapterId}/proofread")
    ProofreadResponse proofread(@PathVariable UUID chapterId, @RequestBody ProofreadRequest request) {
        return new ProofreadResponse(proofreadService.check(currentUser.id(), chapterId, request.blocks()));
    }

    @GetMapping("/books/{bookId}/dictionary")
    List<String> dictionary(@PathVariable UUID bookId) {
        return proofreadService.dictionary(currentUser.id(), bookId);
    }

    @PostMapping("/books/{bookId}/dictionary")
    List<String> addWord(@PathVariable UUID bookId, @RequestBody WordRequest request) {
        return proofreadService.addWord(currentUser.id(), bookId, request.word());
    }

    @DeleteMapping("/books/{bookId}/dictionary")
    List<String> removeWord(@PathVariable UUID bookId, @RequestParam String word) {
        return proofreadService.removeWord(currentUser.id(), bookId, word);
    }

    record ProofreadRequest(List<BlockText> blocks) {
    }

    record ProofreadResponse(List<BlockIssues> blocks) {
    }

    record WordRequest(String word) {
    }
}
