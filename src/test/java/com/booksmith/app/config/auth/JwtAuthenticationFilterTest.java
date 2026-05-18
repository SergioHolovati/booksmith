package com.booksmith.app.config.auth;

import com.booksmith.app.dto.config.UserContextDTO;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private final JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
    private final UserContextDTO userContextDTO = new UserContextDTO();
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userContextDTO);

    @AfterEach
    void tearDown() {
        userContextDTO.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterShouldPopulateUserContextWhenTokenIsValid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenProvider.validateToken("token")).thenReturn(true);
        when(tokenProvider.getUserContextFromToken("token")).thenReturn(
                UserContextDTO.builder().userId("user-id").userName("john").build()
        );

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(userContextDTO.getUserId()).isEqualTo("user-id");
            assertThat(userContextDTO.getUserName()).isEqualTo("john");
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        });

        assertThat(userContextDTO.getUserId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterShouldSkipAuthenticationWhenTokenIsInvalid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenProvider.validateToken("token")).thenReturn(false);

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull()
        );
    }
}
