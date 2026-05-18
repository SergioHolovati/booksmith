package com.booksmith.app.config.auth;

import com.booksmith.app.enums.AuthProvider;
import com.booksmith.app.model.User;
import com.booksmith.app.repository.AuthRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2SuccessHandlerTest {

    private final AuthRepository authRepository = mock(AuthRepository.class);
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final OAuth2SuccessHandler handler = new OAuth2SuccessHandler(authRepository, jwtTokenProvider);

    @Test
    void onAuthenticationSuccessShouldCreateGoogleUserAndRedirectWithJwt() throws Exception {
        ReflectionTestUtils.setField(handler, "successRedirectUrl", "http://localhost:3000/auth/callback");

        OAuth2User oauth2User = mock(OAuth2User.class);
        Authentication authentication = mock(Authentication.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(authentication.getPrincipal()).thenReturn(oauth2User);
        when(oauth2User.getAttribute("email")).thenReturn("john@email.com");
        when(oauth2User.getAttribute("name")).thenReturn("John Doe");
        when(authRepository.findByEmail("john@email.com")).thenReturn(null);
        when(authRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("user-id");
            return user;
        });
        when(jwtTokenProvider.generateToken("user-id", "john")).thenReturn("jwt-token");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("http://localhost:3000/auth/callback?token=Bearer+jwt-token");
    }

    @Test
    void onAuthenticationSuccessShouldUseExistingUser() throws Exception {
        ReflectionTestUtils.setField(handler, "successRedirectUrl", "http://localhost:3000/auth/callback");

        OAuth2User oauth2User = mock(OAuth2User.class);
        Authentication authentication = mock(Authentication.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        User user = User.builder()
                .id("user-id")
                .email("john@email.com")
                .username("john")
                .verified(false)
                .build();

        when(authentication.getPrincipal()).thenReturn(oauth2User);
        when(oauth2User.getAttribute("email")).thenReturn("john@email.com");
        when(oauth2User.getAttribute("name")).thenReturn("John Doe");
        when(authRepository.findByEmail("john@email.com")).thenReturn(user);
        when(authRepository.save(user)).thenReturn(user);
        when(jwtTokenProvider.generateToken("user-id", "john")).thenReturn("jwt-token");

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(user.isVerified()).isTrue();
        assertThat(user.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(user.isGoogleLinked()).isTrue();
        verify(response).sendRedirect("http://localhost:3000/auth/callback?token=Bearer+jwt-token");
    }

    @Test
    void onAuthenticationSuccessShouldLinkGoogleLoginToExistingLocalUser() throws Exception {
        ReflectionTestUtils.setField(handler, "successRedirectUrl", "http://localhost:3000/auth/callback");

        OAuth2User oauth2User = mock(OAuth2User.class);
        Authentication authentication = mock(Authentication.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        User user = User.builder()
                .id("user-id")
                .email("john@email.com")
                .username("john")
                .password("encoded-password")
                .provider(AuthProvider.LOCAL)
                .googleLinked(false)
                .verified(false)
                .emailVerificationTokenHash(123)
                .emailVerificationTokenExpiresAt(java.time.LocalDateTime.now().plusMinutes(10))
                .build();

        when(authentication.getPrincipal()).thenReturn(oauth2User);
        when(oauth2User.getAttribute("email")).thenReturn("john@email.com");
        when(oauth2User.getAttribute("name")).thenReturn("John Doe");
        when(authRepository.findByEmail("john@email.com")).thenReturn(user);
        when(authRepository.save(user)).thenReturn(user);
        when(jwtTokenProvider.generateToken("user-id", "john")).thenReturn("jwt-token");

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(user.getProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(user.isGoogleLinked()).isTrue();
        assertThat(user.isVerified()).isTrue();
        assertThat(user.getEmailVerificationTokenHash()).isNull();
        assertThat(user.getEmailVerificationTokenExpiresAt()).isNull();
        verify(authRepository).save(user);
        verify(response).sendRedirect("http://localhost:3000/auth/callback?token=Bearer+jwt-token");
    }
}
