package com.booksmith.assist.web;

import com.booksmith.assist.internal.AssistService;
import com.booksmith.assist.internal.ReviewService;
import com.booksmith.identity.api.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sugestões de IA (§4.11): o app pergunta se estão ligadas e pede ideias ou perguntas para um campo. */
@RestController
@RequestMapping("/api/v1/assist")
@RequiredArgsConstructor
class AssistController {

    private final AssistService assistService;
    private final ReviewService reviewService;
    private final CurrentUser currentUser;

    @GetMapping("/status")
    AssistService.Status status() {
        return assistService.status(currentUser.id());
    }

    @PostMapping("/suggestions")
    AssistService.Result suggest(@RequestBody AssistService.Request request) {
        return assistService.suggest(currentUser.id(), request);
    }

    /** Revisão do capítulo pela IA, uma parte por vez. */
    @PostMapping("/review")
    ReviewService.Result review(@RequestBody ReviewService.Request request) {
        return reviewService.review(currentUser.id(), request);
    }
}
