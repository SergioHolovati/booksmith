package com.booksmith.manuscript.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.manuscript.internal.WritingStatsService;
import com.booksmith.manuscript.internal.WritingStatsService.Stats;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Meta diária de palavras e sequência de dias (§4.1 do produto). O app manda o fuso do aparelho em
 * {@value #TIME_ZONE_HEADER}, para "hoje" ser o dia de quem escreve.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
class WritingStatsController {

    static final String TIME_ZONE_HEADER = "X-Time-Zone";

    private final WritingStatsService writingStats;
    private final CurrentUser currentUser;

    @GetMapping("/writing-stats")
    Stats stats(@RequestHeader(value = TIME_ZONE_HEADER, required = false) String timeZone) {
        return writingStats.stats(currentUser.id(), WritingStatsService.zone(timeZone));
    }

    /** {@code dailyWords} null tira a meta. */
    @PutMapping("/writing-goal")
    GoalResponse goal(@RequestBody GoalRequest request) {
        return new GoalResponse(writingStats.setGoal(currentUser.id(), request.dailyWords()));
    }

    record GoalRequest(Integer dailyWords) {
    }

    record GoalResponse(Integer dailyWords) {
    }
}
