package com.booksmith.app.service.impl;

import com.booksmith.app.config.auth.JwtTokenProvider;
import com.booksmith.app.dto.request.LoginRequest;
import com.booksmith.app.dto.request.RegisterRequest;
import com.booksmith.app.exception.UnauthorizedException;
import com.booksmith.app.model.User;
import com.booksmith.app.repository.AuthRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginServiceImplTest {

    private final AuthRepository authRepository = mock(AuthRepository.class);
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl(authRepository, jwtTokenProvider, passwordEncoder);

    @Test
    void loginShouldReturnJwtWhenCredentialsAreValid() {
        LoginRequest request = loginRequest("john", "john@email.com", "raw-password");
        User user = User.builder()
                .id("user-id")
                .username("john")
                .email("john@email.com")
                .password("encoded-password")
                .build();
        when(authRepository.findByUsernameOrEmail("john", "john@email.com")).thenReturn(user);
        when(passwordEncoder.matches("raw-password", "encoded-password")).thenReturn(true);
        when(jwtTokenProvider.generateToken("user-id", "john")).thenReturn("token");

        var response = loginService.login(request);

        assertThat(response.getUserId()).isEqualTo("user-id");
        assertThat(response.getUserName()).isEqualTo("john");
        assertThat(response.getJwt()).isEqualTo("Bearer token");
    }

    @Test
    void loginShouldThrowUnauthorizedWhenUserDoesNotExist() {
        LoginRequest request = loginRequest("john", "john@email.com", "raw-password");
        when(authRepository.findByUsernameOrEmail("john", "john@email.com")).thenReturn(null);

        assertThatThrownBy(() -> loginService.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid username or password.");
    }

    @Test
    void loginShouldThrowUnauthorizedWhenPasswordDoesNotMatch() {
        LoginRequest request = loginRequest("john", "john@email.com", "raw-password");
        User user = User.builder().password("encoded-password").build();
        when(authRepository.findByUsernameOrEmail("john", "john@email.com")).thenReturn(user);
        when(passwordEncoder.matches("raw-password", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> loginService.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid username or password.");
    }

    @Test
    void registerShouldEncodePasswordAndSaveUser() {
        RegisterRequest request = new RegisterRequest(
                "John",
                "Doe",
                "john",
                "12345678900",
                "john@email.com",
                "Raw@12345",
                "+5519983592406"
        );
        when(passwordEncoder.encode("Raw@12345")).thenReturn("encoded-password");

        loginService.register(request);

        verify(authRepository).save(any(User.class));
    }

    @Test
    void refreshTokenShouldReturnNewTokenAndInvalidateOldOne() {
        User userContext = User.builder().id("user-id").username("john").build();
        when(jwtTokenProvider.validateToken("token")).thenReturn(true);
        when(jwtTokenProvider.getUserContextFromToken("token")).thenReturn(
                com.booksmith.app.dto.config.UserContext.builder().userId("user-id").userName("john").build()
        );
        when(jwtTokenProvider.refreshToken("token")).thenReturn("refreshed-token");

        var response = loginService.refreshToken("Bearer token");

        assertThat(userContext.getId()).isEqualTo("user-id");
        assertThat(response.getJwt()).isEqualTo("Bearer refreshed-token");
        assertThat(response.getUserId()).isEqualTo("user-id");
        assertThat(response.getUserName()).isEqualTo("john");
    }

    @Test
    void refreshTokenShouldThrowWhenHeaderIsInvalid() {
        assertThatThrownBy(() -> loginService.refreshToken("token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Authorization Bearer token is required.");
    }

    @Test
    void refreshTokenShouldThrowWhenTokenIsInvalid() {
        when(jwtTokenProvider.validateToken("token")).thenReturn(false);

        assertThatThrownBy(() -> loginService.refreshToken("Bearer token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid token.");
    }

    @Test
    void logoutShouldInvalidateToken() {
        when(jwtTokenProvider.validateToken("token")).thenReturn(true);
        when(jwtTokenProvider.getUserContextFromToken("token")).thenReturn(
                com.booksmith.app.dto.config.UserContext.builder().userId("user-id").userName("john").build()
        );

        loginService.logout("Bearer token");

        verify(jwtTokenProvider).invalidateToken("token");
    }

    @Test
    void logoutShouldThrowWhenTokenIsInvalid() {
        when(jwtTokenProvider.validateToken("token")).thenReturn(false);

        assertThatThrownBy(() -> loginService.logout("Bearer token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid token.");
    }

    private LoginRequest loginRequest(String username, String email, String password) {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "username", username);
        ReflectionTestUtils.setField(request, "email", email);
        ReflectionTestUtils.setField(request, "password", password);
        return request;
    }
}
