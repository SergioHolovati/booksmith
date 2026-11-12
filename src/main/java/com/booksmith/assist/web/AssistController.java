package com.booksmith.assist.web;

import com.booksmith.assist.internal.AskService;
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
    private final AskService askService;
    private final CurrentUser currentUser;

    @GetMapping("/status")
    AssistService.Status status() {
        return assistService.status(currentUser.id());
    }

    @PostMapping("/suggestions")
    AssistService.Result suggest(@RequestBody AssistService.Request request) {
        return assistService.suggest(currentUser.id(), request);
    }

    /** Abre a revisão com IA do capítulo: gasta um uso da cota (as chamadas seguintes usam o id devolvido). */
    @PostMapping("/review/start")
    ReviewService.StartResult startReview(@RequestBody ReviewService.StartRequest request) {
        return reviewService.start(currentUser.id(), request);
    }

    /** Revisão do capítulo: a formatação (regras, livre) ou uma das revisões com IA, uma parte por vez. */
    @PostMapping("/review")
    ReviewService.Result review(@RequestBody ReviewService.Request request) {
        return reviewService.review(currentUser.id(), request);
    }

    /** "Pergunte ao seu livro": a resposta da IA, com os trechos de origem conferidos contra o texto. */
    @PostMapping("/ask")
    AskService.Result ask(@RequestBody AskService.Request request) {
        return askService.ask(currentUser.id(), request);
    }
}
