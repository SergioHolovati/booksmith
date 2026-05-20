package com.booksmith.app.service.impl;

import com.booksmith.app.config.auth.JwtTokenProvider;
import com.booksmith.app.dto.request.LoginRequestDTO;
import com.booksmith.app.dto.request.RegisterRequestDTO;
import com.booksmith.app.dto.response.LoginResponseDTO;
import com.booksmith.app.exception.UnauthorizedException;
import com.booksmith.app.model.PasswordResetToken;
import com.booksmith.app.model.User;
import com.booksmith.app.repository.AuthRepository;
import com.booksmith.app.repository.PasswordResetRepository;
import com.booksmith.app.service.AuthService;
import com.booksmith.app.service.EmailService;
import com.resend.core.exception.ResendException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;

import static java.util.Objects.hash;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final String VERIFICATION_TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int VERIFICATION_TOKEN_LENGTH = 6;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AuthRepository authRepository;
    private JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetRepository passwordResetRepository;
    private final EmailService emailService;

    public AuthServiceImpl(AuthRepository authRepository,
                           JwtTokenProvider jwtTokenProvider,
                           PasswordEncoder passwordEncoder,
                           PasswordResetRepository passwordResetRepository,
                           EmailService emailService) {
        this.authRepository = authRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.passwordResetRepository = passwordResetRepository;
        this.emailService = emailService;
    }

    @Override
    public LoginResponseDTO login(LoginRequestDTO loginRequestDTO){
        User user = this.authRepository.findByEmail(loginRequestDTO.getEmail());
        if (user == null) {
            throw new UnauthorizedException("Invalid username or password.");
        }
        boolean matches = passwordEncoder.matches(loginRequestDTO.getPassword(), user.getPassword());
        if (!matches) {
            throw new UnauthorizedException("Invalid username or password.");
        }

        log.info("Successfully Login for userId: {}",user.getId());
        return LoginResponseDTO.builder()
                .token("Bearer " + this.jwtTokenProvider.generateToken(user))
                .userId(user.getId())
                .userName(user.getUsername())
                .build();
    }

    @Override
    public void register(RegisterRequestDTO register) {
        User user = authRepository.findByEmail(register.getEmail());
        if(user != null){
            throw new UnauthorizedException("Account already exists.");
        }
        user = User.toEntity(register);
        user.setPassword(passwordEncoder.encode(register.getPassword()));
        String verificationToken = generateVerificationToken();
        user.setEmailVerificationTokenHash(hash(verificationToken));
        user.setEmailVerificationTokenExpiresAt(LocalDateTime.now().plusMinutes(15));
        this.authRepository.save(user);
        try {
            emailService.sendEmailVerificationEmail(user.getEmail(), verificationToken);
        } catch (ResendException e) {
            throw new RuntimeException(e);
        }
        log.info("Successfully Register for userId: {}",user.getId());
    }

    @Override
    public void verifyEmail(String email, String token) {
        User user = authRepository.findByEmail(email);
        if (user == null) {
            throw new UnauthorizedException("Invalid verification token.");
        }

        if (user.isVerified()) {
            return;
        }

        boolean tokenMatches = user.getEmailVerificationTokenHash() != null
                && user.getEmailVerificationTokenHash().equals(hash(token));
        boolean tokenValid = user.getEmailVerificationTokenExpiresAt() != null
                && LocalDateTime.now().isBefore(user.getEmailVerificationTokenExpiresAt());

        if (!tokenMatches || !tokenValid) {
            throw new UnauthorizedException("Invalid verification token.");
        }

        user.setVerified(true);
        user.setEmailVerificationTokenHash(null);
        user.setEmailVerificationTokenExpiresAt(null);
        authRepository.save(user);
    }

    @Override
    public LoginResponseDTO refreshToken(String authorizationHeader) {
        String token = recoverToken(authorizationHeader);
        if (!jwtTokenProvider.validateToken(token)) {
            throw new UnauthorizedException("Invalid token.");
        }

        var userContext = jwtTokenProvider.getUserContextFromToken(token);
        User user = authRepository.findById(userContext.getUserId())
                .orElseThrow(()->new UnauthorizedException("Invalid user id."));
        String refreshedToken = jwtTokenProvider.refreshToken(token,user);
        log.info("Successfully refreshed token for userId: {}", userContext.getUserId());
        return LoginResponseDTO.builder()
                .token("Bearer " + refreshedToken)
                .userId(user.getId())
                .userName(user.getUsername())
                .authorId(ObjectUtils.isEmpty(user.getAuthor()) ? "-" : user.getAuthor().getId())
                .build();
    }

    @Override
    public void logout(String authorizationHeader) {
        String token = recoverToken(authorizationHeader);
        if (!jwtTokenProvider.validateToken(token)) {
            throw new UnauthorizedException("Invalid token.");
        }

        var userContext = jwtTokenProvider.getUserContextFromToken(token);
        jwtTokenProvider.invalidateToken(token);
        log.info("Successfully logout for userId: {}", userContext.getUserId());
    }

    private String recoverToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new UnauthorizedException("Authorization Bearer token is required.");
        }
        return authorizationHeader.substring(7);
    }

    private String generateVerificationToken() {
        StringBuilder token = new StringBuilder(VERIFICATION_TOKEN_LENGTH);
        for (int i = 0; i < VERIFICATION_TOKEN_LENGTH; i++) {
            int index = SECURE_RANDOM.nextInt(VERIFICATION_TOKEN_CHARS.length());
            token.append(VERIFICATION_TOKEN_CHARS.charAt(index));
        }
        return token.toString();
    }

    public void forgotPassword(String email) {

        var user = authRepository.findByEmail(email);

        if (user.getId().isEmpty()) {
            return;
        }

        String token = UUID.randomUUID().toString();

        passwordResetRepository.save(
                PasswordResetToken.builder()
                        .user(user)
                        .tokenHash(hash(token))
                        .expiresAt(
                                LocalDateTime.now().plusMinutes(15)
                        )
                        .build()
        );

        try {
            emailService.sendPasswordRecoveryEmail(
                    email,
                    token
            );
        } catch (ResendException e) {
            throw new RuntimeException(e);
        }
    }

}
