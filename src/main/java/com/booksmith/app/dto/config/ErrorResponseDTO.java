package com.booksmith.app.dto.config;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class ErrorResponseDTO {
    private LocalDateTime timestamp;
    private int status;
    private List<String> messages;

    public ErrorResponseDTO(int value, List<String> errors) {
        timestamp = LocalDateTime.now();
        this.status = value;
        this.messages = errors;
    }
}
