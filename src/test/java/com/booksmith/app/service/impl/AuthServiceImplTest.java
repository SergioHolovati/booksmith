package com.booksmith.app.service.impl;

import com.booksmith.app.config.auth.JwtTokenProvider;
import com.booksmith.app.dto.config.UserContextDTO;
import com.booksmith.app.dto.request.LoginRequestDTO;
import com.booksmith.app.dto.request.RegisterRequestDTO;
import com.booksmith.app.enums.AuthProvider;
import com.booksmith.app.exception.UnauthorizedException;
import com.booksmith.app.model.User;
import com.booksmith.app.repository.AuthRepository;
import com.booksmith.app.repository.PasswordResetRepository;
import com.booksmith.app.service.EmailService;
import com.resend.core.exception.ResendException;
import org.apache.coyote.BadRequestException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private final AuthRepository authRepository = mock(AuthRepository.class);
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final PasswordResetRepository passwordResetRepository = mock(PasswordResetRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    private final AuthServiceImpl loginService = new AuthServiceImpl(authRepository,
                                                                     jwtTokenProvider,
                                                                     passwordEncoder,
                                                                     passwordResetRepository,
                                                                     emailService);

    @Test
    void loginShouldReturnJwtWhenCredentialsAreValid() throws BadRequestException {
        LoginRequestDTO request = loginRequest("john", "john@email.com", "raw-password");
        User user = User.builder()
                .id("user-id")
                .username("john")
                .email("john@email.com")
                .password("encoded-password")
                .build();
        when(authRepository.findByUsernameOrEmail("john", "john@email.com")).thenReturn(user);
        when(passwordEncoder.matches("raw-password", "encoded-password")).thenReturn(true);
        when(jwtTokenProvider.generateToken(user)).thenReturn("token");

        var response = loginService.login(request);

        assertThat(response.getUserId()).isEqualTo("user-id");
        assertThat(response.getUserName()).isEqualTo("john");
        assertThat(response.getToken()).isEqualTo("Bearer token");
    }

    @Test
    void loginShouldThrowUnauthorizedWhenUserDoesNotExist() throws BadRequestException {
        LoginRequestDTO request = loginRequest("john", "john@email.com", "raw-password");
        when(authRepository.findByUsernameOrEmail("john", "john@email.com")).thenReturn(null);

        assertThatThrownBy(() -> loginService.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid username or password.");
    }

    @Test
    void loginShouldThrowUnauthorizedWhenPasswordDoesNotMatch() throws BadRequestException {
        LoginRequestDTO request = loginRequest("john", "john@email.com", "raw-password");
        User user = User.builder().password("encoded-password").build();
        when(authRepository.findByUsernameOrEmail("john", "john@email.com")).thenReturn(user);
        when(passwordEncoder.matches("raw-password", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> loginService.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid username or password.");
    }

    @Test
    void registerShouldEncodePasswordSaveUserAsUnverifiedAndSendVerificationEmail() throws ResendException {
        RegisterRequestDTO request = new RegisterRequestDTO(
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

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(authRepository).save(userCaptor.capture());
        verify(emailService).sendEmailVerificationEmail(eq("john@email.com"), tokenCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getPassword()).isEqualTo("encoded-password");
        assertThat(savedUser.getProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(savedUser.isGoogleLinked()).isFalse();
        assertThat(savedUser.isVerified()).isFalse();
        assertThat(savedUser.getEmailVerificationTokenHash()).isNotNull();
        assertThat(savedUser.getEmailVerificationTokenExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(tokenCaptor.getValue()).matches("[A-Z0-9]{6}");
    }

    @Test
    void verifyEmailShouldMarkUserAsVerifiedWhenTokenIsValid() {
        User user = User.builder()
                .email("john@email.com")
                .verified(false)
                .emailVerificationTokenHash(java.util.Objects.hash("ABC123"))
                .emailVerificationTokenExpiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        when(authRepository.findByEmail("john@email.com")).thenReturn(user);

        loginService.verifyEmail("john@email.com", "ABC123");

        assertThat(user.isVerified()).isTrue();
        assertThat(user.getEmailVerificationTokenHash()).isNull();
        assertThat(user.getEmailVerificationTokenExpiresAt()).isNull();
        verify(authRepository).save(user);
    }

    @Test
    void verifyEmailShouldThrowWhenTokenIsInvalid() {
        User user = User.builder()
                .email("john@email.com")
                .verified(false)
                .emailVerificationTokenHash(java.util.Objects.hash("ABC123"))
                .emailVerificationTokenExpiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        when(authRepository.findByEmail("john@email.com")).thenReturn(user);

        assertThatThrownBy(() -> loginService.verifyEmail("john@email.com", "ZZZ999"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid verification token.");
    }

    @Test
    void refreshTokenShouldReturnNewTokenAndInvalidateOldOne() {
        User userContext = User.builder().id("user-id").username("john").build();
        when(jwtTokenProvider.validateToken("token")).thenReturn(true);
        when(jwtTokenProvider.getUserContextFromToken("token")).thenReturn(
                UserContextDTO.builder().userId("user-id").userName("john").build()
        );
        when(jwtTokenProvider.refreshToken("token")).thenReturn("refreshed-token");

        var response = loginService.refreshToken("Bearer token");

        assertThat(userContext.getId()).isEqualTo("user-id");
        assertThat(response.getToken()).isEqualTo("Bearer refreshed-token");
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
                UserContextDTO.builder().userId("user-id").userName("john").build()
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

    private LoginRequestDTO loginRequest(String username, String email, String password) throws BadRequestException {
        LoginRequestDTO request = new LoginRequestDTO();
        ReflectionTestUtils.setField(request, "username", username);
        ReflectionTestUtils.setField(request, "email", email);
        ReflectionTestUtils.setField(request, "password", password);
        return request;
    }
}
