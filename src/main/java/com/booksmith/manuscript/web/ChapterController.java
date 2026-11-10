package com.booksmith.manuscript.web;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.CurrentUser;
import com.booksmith.manuscript.internal.Chapter;
import com.booksmith.manuscript.internal.ChapterService;
import com.booksmith.manuscript.internal.WritingStatsService;
import com.booksmith.manuscript.web.ManuscriptDtos.ChapterResponse;
import com.booksmith.manuscript.web.ManuscriptDtos.ChapterSummary;
import com.booksmith.manuscript.web.ManuscriptDtos.CreateChapterRequest;
import com.booksmith.manuscript.web.ManuscriptDtos.RenameChapterRequest;
import com.booksmith.manuscript.web.ManuscriptDtos.ReorderRequest;
import com.booksmith.manuscript.web.ManuscriptDtos.SaveContentRequest;
import com.booksmith.manuscript.web.ManuscriptDtos.SaveResult;
import com.booksmith.manuscript.web.ManuscriptDtos.SaveVersionRequest;
import com.booksmith.manuscript.web.ManuscriptDtos.VersionDetail;
import com.booksmith.manuscript.web.ManuscriptDtos.VersionSummary;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class ChapterController {

    private final ChapterService chapterService;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper;

    @PostMapping("/books/{bookId}/chapters")
    @ResponseStatus(HttpStatus.CREATED)
    ChapterResponse create(@PathVariable UUID bookId, @RequestBody(required = false) @Valid CreateChapterRequest request) {
        String title = request == null ? null : request.title();
        return ChapterResponse.from(chapterService.create(currentUser.id(), bookId, title), jsonMapper);
    }

    @PutMapping("/books/{bookId}/chapters/order")
    List<ChapterSummary> reorder(@PathVariable UUID bookId, @RequestBody @Valid ReorderRequest request) {
        return chapterService.reorder(currentUser.id(), bookId, request.chapterIds()).stream()
                .map(ChapterSummary::from)
                .toList();
    }

    @GetMapping("/chapters/{chapterId}")
    ResponseEntity<ChapterResponse> get(@PathVariable UUID chapterId) {
        var view = chapterService.get(currentUser.id(), chapterId);
        return ResponseEntity.ok().eTag(etag(view.chapter())).body(ChapterResponse.from(view, jsonMapper));
    }

    /** Autosave. Exige {@code If-Match: "<versão>"}; responde 409 se outro dispositivo salvou antes. */
    @PutMapping("/chapters/{chapterId}/content")
    ResponseEntity<SaveResult> saveContent(@PathVariable UUID chapterId,
                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                           @RequestHeader(value = WritingStatsController.TIME_ZONE_HEADER, required = false) String timeZone,
                                           @RequestBody @Valid SaveContentRequest request) {
        Chapter chapter = chapterService.saveContent(currentUser.id(), chapterId, parseVersion(ifMatch), request.content(),
                WritingStatsService.zone(timeZone));
        return ResponseEntity.ok().eTag(etag(chapter)).body(SaveResult.from(chapter));
    }

    /** Começa, renomeia ou ({@code title} vazio) tira a parte que este capítulo abre. */
    @PutMapping("/chapters/{chapterId}/part")
    ResponseEntity<ChapterSummary> part(@PathVariable UUID chapterId, @RequestBody PartRequest request) {
        Chapter chapter = chapterService.setPart(currentUser.id(), chapterId, request.title());
        return ResponseEntity.ok().eTag(etag(chapter)).body(ChapterSummary.from(chapter));
    }

    record PartRequest(String title) {
    }

    @PatchMapping("/chapters/{chapterId}")
    ResponseEntity<ChapterSummary> rename(@PathVariable UUID chapterId, @RequestBody @Valid RenameChapterRequest request) {
        Chapter chapter = chapterService.rename(currentUser.id(), chapterId, request.title());
        return ResponseEntity.ok().eTag(etag(chapter)).body(ChapterSummary.from(chapter));
    }

    @DeleteMapping("/chapters/{chapterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID chapterId) {
        chapterService.delete(currentUser.id(), chapterId);
    }

    // ===== Histórico de versões =====

    @GetMapping("/chapters/{chapterId}/versions")
    List<VersionSummary> versions(@PathVariable UUID chapterId) {
        return chapterService.listVersions(currentUser.id(), chapterId).stream().map(VersionSummary::from).toList();
    }

    @GetMapping("/chapters/{chapterId}/versions/{versionId}")
    VersionDetail version(@PathVariable UUID chapterId, @PathVariable UUID versionId) {
        return VersionDetail.from(chapterService.getVersion(currentUser.id(), chapterId, versionId), jsonMapper);
    }

    /** "Salvar versão agora", com nome opcional. */
    @PostMapping("/chapters/{chapterId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    VersionSummary saveVersion(@PathVariable UUID chapterId,
                               @RequestBody(required = false) @Valid SaveVersionRequest request) {
        String label = request == null ? null : request.label();
        return VersionSummary.from(chapterService.saveVersion(currentUser.id(), chapterId, label));
    }

    /** Volta para a versão escolhida. Exige If-Match com a versão atual; o estado atual vira versão antes. */
    @PostMapping("/chapters/{chapterId}/versions/{versionId}/restore")
    ResponseEntity<ChapterResponse> restore(@PathVariable UUID chapterId, @PathVariable UUID versionId,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        var view = chapterService.restoreVersion(currentUser.id(), chapterId, versionId, parseVersion(ifMatch));
        return ResponseEntity.ok().eTag(etag(view.chapter())).body(ChapterResponse.from(view, jsonMapper));
    }

    private static String etag(Chapter chapter) {
        return "\"" + chapter.getVersion() + "\"";
    }

    private static long parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "if_match_required",
                    "Envie o header If-Match com a versão do capítulo.");
        }
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("if_match_invalid", "If-Match inválido.");
        }
    }
}
