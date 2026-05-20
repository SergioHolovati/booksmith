package com.booksmith.app.config.auth;

import com.booksmith.app.dto.config.UserContextDTO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserContextDTO userContextDTO;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   UserContextDTO userContextDTO) {
        this.tokenProvider = tokenProvider;
        this.userContextDTO = userContextDTO;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        try {
            String token = recoverToken(request);

            if (token != null && tokenProvider.validateToken(token)) {

                UserContextDTO authenticatedUser =
                        tokenProvider.getUserContextFromToken(token);

                userContextDTO.setAuthenticatedUser(authenticatedUser);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                authenticatedUser.getUserName(),
                                null,
                                Collections.emptyList()
                        );

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }

            filterChain.doFilter(request, response);

        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            userContextDTO.clear();

            // ainda continua request mesmo com erro de parsing
            filterChain.doFilter(request, response);

        } finally {
            SecurityContextHolder.clearContext();
            userContextDTO.clear();
        }
    }

    private String recoverToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }

        return authHeader.substring(7);
    }
}