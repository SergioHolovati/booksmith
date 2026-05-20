package com.booksmith.app.controller;

import com.booksmith.app.dto.request.LoginRequestDTO;
import com.booksmith.app.dto.request.EmailVerificationRequestDTO;
import com.booksmith.app.dto.request.RegisterRequestDTO;
import com.booksmith.app.dto.response.LoginResponseDTO;
import com.booksmith.app.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final AuthController authController = new AuthController(authService);

    @Test
    void loginShouldReturnServiceResponse() throws Exception {
        LoginRequestDTO request = new LoginRequestDTO();
        LoginResponseDTO response = LoginResponseDTO.builder()
                .userId("user-id")
                .userName("john")
                .token("Bearer token")
                .build();
        when(authService.login(request)).thenReturn(response);

        var result = authController.login(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(response);
    }

    @Test
    void googleLoginShouldRedirectToGoogleOAuthAuthorization() {
        var result = authController.googleLogin();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(result.getHeaders().getLocation().toString())
                .isEqualTo("/oauth2/authorization/google");
    }

    @Test
    void registerShouldReturnCreated() {
        RegisterRequestDTO request = new RegisterRequestDTO();

        var result = authController.register(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(authService).register(request);
    }

    @Test
    void verifyEmailShouldReturnNoContent() {
        EmailVerificationRequestDTO request = new EmailVerificationRequestDTO();
        request.setEmail("john@email.com");
        request.setToken("ABC123");

        var result = authController.verifyEmail(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(authService).verifyEmail("john@email.com", "ABC123");
    }

    @Test
    void refreshTokenShouldReturnServiceResponse() {
        LoginResponseDTO response = LoginResponseDTO.builder().token("Bearer refreshed-token").build();
        when(authService.refreshToken("Bearer token")).thenReturn(response);

        var result = authController.refreshToken("Bearer token");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(response);
    }

    @Test
    void logoutShouldReturnNoContent() {
        var result = authController.logout("Bearer token");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(authService).logout("Bearer token");
    }
}
