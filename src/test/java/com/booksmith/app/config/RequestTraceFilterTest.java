package com.booksmith.app.config;

import com.booksmith.app.dto.config.UserContext;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTraceFilterTest {

    private final UserContext userContext = new UserContext();
    private final RequestTraceFilter filter = new RequestTraceFilter(userContext);

    @AfterEach
    void tearDown() {
        userContext.clear();
        MDC.clear();
    }

    @Test
    void doFilterShouldRequireTraceIdOutsideLogin() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/profile");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getContentAsString()).contains("Header X-Request-Trace-Id is required.");
    }

    @Test
    void doFilterShouldPopulateContextAndResponseHeaderWhenTraceIdExists() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/profile");
        request.addHeader("X-Request-Trace-Id", "trace-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(userContext.getRequestTraceId()).isEqualTo("trace-id");
            assertThat(MDC.get("requestTraceId")).isEqualTo("trace-id");
        });

        assertThat(response.getHeader("X-Request-Trace-Id")).isEqualTo("trace-id");
        assertThat(userContext.getRequestTraceId()).isNull();
        assertThat(MDC.get("requestTraceId")).isNull();
    }

    @Test
    void doFilterShouldGenerateTraceIdForLoginWhenHeaderIsMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getHeader("X-Request-Trace-Id")).isNotBlank();
    }
}
