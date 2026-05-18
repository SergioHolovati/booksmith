package com.booksmith.app.service.impl;

import com.booksmith.app.dto.config.UserContext;
import com.booksmith.app.model.User;
import com.booksmith.app.repository.ProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfileServiceImplTest {

    private final ProfileRepository profileRepository = mock(ProfileRepository.class);
    private final UserContext userContext = new UserContext();
    private final ProfileServiceImpl profileService = new ProfileServiceImpl(profileRepository, userContext);

    @AfterEach
    void tearDown() {
        userContext.clear();
    }

    @Test
    void profileShouldReturnCurrentUserProfile() {
        userContext.setAuthenticatedUser(UserContext.builder().userId("user-id").userName("john").build());
        User user = User.builder()
                .id("user-id")
                .username("john")
                .firstName("John")
                .email("john@email.com")
                .phone("+5519983592406")
                .document("12345678900")
                .accountStatus("active")
                .build();
        when(profileRepository.getReferenceById("user-id")).thenReturn(user);

        var response = profileService.profile();

        assertThat(response.getId()).isEqualTo("user-id");
        assertThat(response.getUsername()).isEqualTo("john");
        assertThat(response.getName()).isEqualTo("John");
    }
}
