package com.booksmith.app.dto.config;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
@Builder
public class PasswordResetTokenDTO {
    String user;
    int tokenHash;
    LocalDateTime expiresAt;
}
