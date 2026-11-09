package com.booksmith.manuscript.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Política e gravação do histórico de versões (§9.2 da arquitetura).
 *
 * <p>Ao salvar, o estado <em>anterior</em> do capítulo vira uma versão automática quando passou tempo suficiente
 * desde a última versão ou quando o texto mudou muito. Assim o histórico tem pontos úteis ("como estava ontem",
 * "antes de reescrever o final") sem uma versão a cada autosave.
 */
@Component
@RequiredArgsConstructor
class ChapterHistory {

    static final Duration MIN_INTERVAL = Duration.ofMinutes(10);
    static final int MIN_WORD_CHANGE = 500;

    private final ChapterVersionRepository versionRepository;
    private final Clock clock;

    /** Guarda o estado atual do capítulo como versão automática, se a política mandar. */
    void snapshotIfDue(Chapter current, ManuscriptDocument incoming) {
        Optional<ChapterVersion> last = versionRepository.findFirstByChapterIdOrderByCreatedAtDesc(current.getId());
        if (isDue(current.getWordCount(), last.orElse(null), incoming.wordCount(), Instant.now(clock))) {
            snapshot(current, ChapterVersion.Reason.AUTOMATIC, null);
        }
    }

    ChapterVersion snapshot(Chapter chapter, ChapterVersion.Reason reason, String label) {
        return versionRepository.save(ChapterVersion.builder()
                .chapterId(chapter.getId())
                .chapterVersion(chapter.getVersion())
                .title(chapter.getTitle())
                .content(chapter.getContent())
                .wordCount(chapter.getWordCount())
                .reason(reason)
                .label(label == null || label.isBlank() ? null : label.trim())
                .createdAt(Instant.now(clock))
                .build());
    }

    static boolean isDue(int currentWords, ChapterVersion last, int incomingWords, Instant now) {
        if (currentWords == 0) {
            return false; // capítulo vazio: nada a preservar
        }
        if (last == null) {
            return true; // primeira vez que há texto a perder
        }
        boolean enoughTime = Duration.between(last.getCreatedAt(), now).compareTo(MIN_INTERVAL) >= 0;
        boolean bigChange = Math.abs(incomingWords - last.getWordCount()) >= MIN_WORD_CHANGE;
        return enoughTime || bigChange;
    }
}
