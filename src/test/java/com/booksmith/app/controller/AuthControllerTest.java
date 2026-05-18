package com.booksmith.app.controller;

import com.booksmith.app.dto.request.LoginRequest;
import com.booksmith.app.dto.request.RegisterRequest;
import com.booksmith.app.dto.response.LoginResponse;
import com.booksmith.app.service.LoginService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final LoginService loginService = mock(LoginService.class);
    private final AuthController authController = new AuthController(loginService);

    @Test
    void loginShouldReturnServiceResponse() throws Exception {
        LoginRequest request = new LoginRequest();
        LoginResponse response = LoginResponse.builder()
                .userId("user-id")
                .userName("john")
                .jwt("Bearer token")
                .build();
        when(loginService.login(request)).thenReturn(response);

        var result = authController.login(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(response);
    }

    @Test
    void registerShouldReturnCreated() {
        RegisterRequest request = new RegisterRequest();

        var result = authController.register(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(loginService).register(request);
    }

    @Test
    void refreshTokenShouldReturnServiceResponse() {
        LoginResponse response = LoginResponse.builder().jwt("Bearer refreshed-token").build();
        when(loginService.refreshToken("Bearer token")).thenReturn(response);

        var result = authController.refreshToken("Bearer token");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(response);
    }

    @Test
    void logoutShouldReturnNoContent() {
        var result = authController.logout("Bearer token");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(loginService).logout("Bearer token");
    }
}
