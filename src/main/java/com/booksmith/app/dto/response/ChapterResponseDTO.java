package com.booksmith.app.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
public class ChapterResponseDTO {
    private String id;
    private String title;
    private String description;
    private String content;
    private LocalDateTime updatedAt;
}
