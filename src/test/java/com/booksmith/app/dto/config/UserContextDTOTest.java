package com.booksmith.app.dto.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextDTOTest {

    private final UserContextDTO userContextDTO = new UserContextDTO();

    @AfterEach
    void tearDown() {
        userContextDTO.clear();
    }

    @Test
    void shouldStoreTraceAndAuthenticatedUserInThreadLocalContext() {
        userContextDTO.setRequestTraceId("trace-id");
        userContextDTO.setAuthenticatedUser(UserContextDTO.builder()
                .userId("user-id")
                .userName("john")
                .build());

        assertThat(userContextDTO.getRequestTraceId()).isEqualTo("trace-id");
        assertThat(userContextDTO.getUserId()).isEqualTo("user-id");
        assertThat(userContextDTO.getUserName()).isEqualTo("john");
        assertThat(userContextDTO.toString()).contains("trace-id", "user-id", "john");
    }

    @Test
    void clearAuthenticatedUserShouldKeepTraceId() {
        userContextDTO.setRequestTraceId("trace-id");
        userContextDTO.setAuthenticatedUser(UserContextDTO.builder()
                .userId("user-id")
                .userName("john")
                .build());

        userContextDTO.clearAuthenticatedUser();

        assertThat(userContextDTO.getRequestTraceId()).isEqualTo("trace-id");
        assertThat(userContextDTO.getUserId()).isNull();
        assertThat(userContextDTO.getUserName()).isNull();
    }

    @Test
    void clearShouldRemoveAllThreadLocalValues() {
        userContextDTO.setRequestTraceId("trace-id");

        userContextDTO.clear();

        assertThat(userContextDTO.getRequestTraceId()).isNull();
    }
}
