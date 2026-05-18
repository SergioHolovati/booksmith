package com.booksmith.app.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleForbiddenExceptionShouldReturnUnauthorized() {
        var response = handler.handleForbiddenException(new UnauthorizedException("Invalid token."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessages()).containsExactly("Invalid token.");
    }

    @Test
    void handleGeneralExceptionShouldReturnInternalServerError() {
        var response = handler.handleGeneralException(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessages()).containsExactly("Ocorreu um erro interno no servidor.");
    }
}
