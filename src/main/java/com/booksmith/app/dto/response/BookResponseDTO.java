package com.booksmith.app.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Setter
@Getter
@Builder
public class BookResponseDTO {

    private String id;
    private String title;
    private String description;
    private String coverPath;
    private boolean published;
    private LocalDate publishedDate;
    private LocalDateTime updatedAt;
    private List<ChapterResponseDTO> chapter;
}
