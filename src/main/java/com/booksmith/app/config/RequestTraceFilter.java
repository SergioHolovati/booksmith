package com.booksmith.app.config;

import com.booksmith.app.dto.config.UserContextDTO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public class RequestTraceFilter extends OncePerRequestFilter {

    private static final String REQUEST_TRACE_ID = "requestTraceId";
    private static final String REQUEST_TRACE_ID_HEADER = "X-Request-Trace-Id";
    private static final List<String> LOGIN_PATH = List.of("/auth/login",
                                                           "/auth/forgot-password",
                                                           "/auth/register",
                                                           "/auth/google",
                                                           "/oauth2/authorization/google",
                                                            "/login/oauth2/code/google",
            "/favicon.ico",
            "/login");

    private final UserContextDTO userContextDTO;

    public RequestTraceFilter(UserContextDTO userContextDTO) {
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
        String requestTraceId = resolveRequestTraceId(request);
        if (isMissingRequestTraceId(requestTraceId)) {
            requestTraceId = UUID.randomUUID().toString();
        }

        userContextDTO.setRequestTraceId(requestTraceId);
        MDC.put(REQUEST_TRACE_ID, requestTraceId);
        response.setHeader(REQUEST_TRACE_ID_HEADER, requestTraceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_TRACE_ID);
            userContextDTO.clear();
        }
    }

    private String resolveRequestTraceId(HttpServletRequest request) {
        return request.getHeader(REQUEST_TRACE_ID_HEADER);
    }

    private boolean isMissingRequestTraceId(String requestTraceId) {
        return requestTraceId == null || requestTraceId.isBlank();
    }

    private boolean isLoginRequest(HttpServletRequest request) {
        return LOGIN_PATH.contains(request.getRequestURI());
    }
}
