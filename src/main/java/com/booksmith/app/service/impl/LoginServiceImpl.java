package com.booksmith.app.service.impl;

import com.booksmith.app.config.auth.JwtTokenProvider;
import com.booksmith.app.dto.request.LoginRequest;
import com.booksmith.app.dto.request.RegisterRequest;
import com.booksmith.app.dto.response.LoginResponse;
import com.booksmith.app.exception.UnauthorizedException;
import com.booksmith.app.model.User;
import com.booksmith.app.repository.AuthRepository;
import com.booksmith.app.service.LoginService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LoginServiceImpl implements LoginService {

    private AuthRepository authRepository;
    private JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    public LoginServiceImpl(AuthRepository authRepository, JwtTokenProvider jwtTokenProvider, PasswordEncoder passwordEncoder) {
        this.authRepository = authRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public LoginResponse login(LoginRequest loginRequest){
        User user = this.authRepository.findByUsernameOrEmail(loginRequest.getUsername(),loginRequest.getEmail());
        if (user == null) {
            throw new UnauthorizedException("Invalid username or password.");
        }
        boolean matches = passwordEncoder.matches(loginRequest.getPassword(), user.getPassword());
        if (!matches) {
            throw new UnauthorizedException("Invalid username or password.");
        }

        log.info("Successfully Login for userId: {}",user.getId());
        return LoginResponse.builder()
                .jwt("Bearer " + this.jwtTokenProvider.generateToken(user.getId(), user.getUsername()))
                .userId(user.getId())
                .userName(user.getUsername())
                .build();
    }

    @Override
    public void register(RegisterRequest register) {
        User user = User.toEntity(register);
        user.setPassword(passwordEncoder.encode(register.getPassword()));
        this.authRepository.save(user);
        log.info("Successfully Register for userId: {}",user.getId());
    }

    @Override
    public LoginResponse refreshToken(String authorizationHeader) {
        String token = recoverToken(authorizationHeader);
        if (!jwtTokenProvider.validateToken(token)) {
            throw new UnauthorizedException("Invalid token.");
        }

        var userContext = jwtTokenProvider.getUserContextFromToken(token);
        String refreshedToken = jwtTokenProvider.refreshToken(token);
        log.info("Successfully refreshed token for userId: {}", userContext.getUserId());
        return LoginResponse.builder()
                .jwt("Bearer " + refreshedToken)
                .userId(userContext.getUserId())
                .userName(userContext.getUserName())
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

}
