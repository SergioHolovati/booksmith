package com.booksmith.manuscript.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.manuscript.internal.MentionService;
import com.booksmith.manuscript.internal.MentionService.MentionedIn;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** "Aparece em": capítulos que mencionam um personagem, lugar ou ficha do mundo com "@". Só os livros da pessoa. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class MentionController {

    private final MentionService mentionService;
    private final CurrentUser currentUser;

    @GetMapping("/mentions/{entityId}")
    List<MentionedIn> mentions(@PathVariable UUID entityId) {
        return mentionService.chaptersMentioning(currentUser.id(), entityId);
    }
}
