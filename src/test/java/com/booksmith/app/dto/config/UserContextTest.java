package com.booksmith.app.dto.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextTest {

    private final UserContext userContext = new UserContext();

    @AfterEach
    void tearDown() {
        userContext.clear();
    }

    @Test
    void shouldStoreTraceAndAuthenticatedUserInThreadLocalContext() {
        userContext.setRequestTraceId("trace-id");
        userContext.setAuthenticatedUser(UserContext.builder()
                .userId("user-id")
                .userName("john")
                .build());

        assertThat(userContext.getRequestTraceId()).isEqualTo("trace-id");
        assertThat(userContext.getUserId()).isEqualTo("user-id");
        assertThat(userContext.getUserName()).isEqualTo("john");
        assertThat(userContext.toString()).contains("trace-id", "user-id", "john");
    }

    @Test
    void clearAuthenticatedUserShouldKeepTraceId() {
        userContext.setRequestTraceId("trace-id");
        userContext.setAuthenticatedUser(UserContext.builder()
                .userId("user-id")
                .userName("john")
                .build());

        userContext.clearAuthenticatedUser();

        assertThat(userContext.getRequestTraceId()).isEqualTo("trace-id");
        assertThat(userContext.getUserId()).isNull();
        assertThat(userContext.getUserName()).isNull();
    }

    @Test
    void clearShouldRemoveAllThreadLocalValues() {
        userContext.setRequestTraceId("trace-id");

        userContext.clear();

        assertThat(userContext.getRequestTraceId()).isNull();
    }
}
