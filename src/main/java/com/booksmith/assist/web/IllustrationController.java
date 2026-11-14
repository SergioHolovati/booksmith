package com.booksmith.assist.web;

import com.booksmith.assist.internal.illustration.IllustrationService;
import com.booksmith.identity.api.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ilustrações com IA (Estúdio Visual): retrato de personagem e cena de capítulo. O pedido é o formulário (opções
 * fixas e textos curtos); o texto enviado ao gerador é montado no servidor. Gerar leva de 10 a 60 segundos.
 */
@RestController
@RequestMapping("/api/v1/assist/illustrations")
@RequiredArgsConstructor
class IllustrationController {

    private final IllustrationService illustrations;
    private final CurrentUser currentUser;

    /** Se está ligado e quantas ilustrações restam nesta semana. */
    @GetMapping("/status")
    IllustrationService.Status status() {
        return illustrations.status(currentUser.id());
    }

    @PostMapping
    IllustrationService.Result generate(@RequestBody IllustrationService.Request request) {
        return illustrations.generate(currentUser.id(), request);
    }
}
