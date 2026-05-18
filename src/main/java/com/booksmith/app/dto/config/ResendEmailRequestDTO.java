package com.booksmith.app.dto.config;

import java.util.List;

public record ResendEmailRequestDTO(
        String from,
        List<String> to,
        String subject,
        String html
) {
}