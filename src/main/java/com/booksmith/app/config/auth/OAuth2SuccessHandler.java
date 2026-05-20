package com.booksmith.app.config.auth;

import com.booksmith.app.enums.AuthProvider;
import com.booksmith.app.exception.UnauthorizedException;
import com.booksmith.app.model.User;
import com.booksmith.app.repository.AuthRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler
        implements AuthenticationSuccessHandler {

    private final AuthRepository authRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.oauth2.success-redirect-url:http://localhost:4200/auth/callback}")
    private String successRedirectUrl;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {

        OAuth2User oauthUser =
                (OAuth2User) authentication.getPrincipal();

        String email =
                oauthUser.getAttribute("email");

        String name =
                oauthUser.getAttribute("name");

        User user = authRepository.findByEmail(email);
        if (user == null) {
            user = createGoogleUser(email, name);
        }
        if(!user.isGoogleLinked()){
                throw new UnauthorizedException("This user is not a google account");
            }

        String token = jwtTokenProvider.generateToken(user);
        String encodedToken = URLEncoder.encode("Bearer " + token, StandardCharsets.UTF_8);

        response.sendRedirect(successRedirectUrl + "?token=" + encodedToken);
    }

    private User createGoogleUser(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setFirstName(extractFirstName(name));
        user.setLastName(extractLastName(name));
        user.setUsername(buildUsername(email));
        user.setProvider(AuthProvider.GOOGLE);
        user.setGoogleLinked(true);
        user.setVerified(true);
        user.setAccountStatus("active");
        return authRepository.save(user);
    }

    private User linkGoogleLogin(User user) {
        user.setVerified(true);
        user.setGoogleLinked(true);

        if (user.getProvider() == null) {
            user.setProvider(user.getPassword() == null ? AuthProvider.GOOGLE : AuthProvider.LOCAL);
        }

        user.setEmailVerificationTokenHash(null);
        user.setEmailVerificationTokenExpiresAt(null);
        return authRepository.save(user);
    }

    private String extractFirstName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim().split("\\s+", 2)[0];
    }

    private String extractLastName(String name) {
        if (name == null || name.isBlank() || !name.trim().contains(" ")) {
            return null;
        }
        return name.trim().split("\\s+", 2)[1];
    }

    private String buildUsername(String email) {
        if (email == null || !email.contains("@")) {
            return null;
        }
        return email.substring(0, email.indexOf("@"));
    }
}
