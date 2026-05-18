package com.booksmith.app.controller;

import com.booksmith.app.dto.response.ProfileResponseDTO;
import com.booksmith.app.service.ProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfileControllerTest {

    private final ProfileService profileService = mock(ProfileService.class);
    private final ProfileController profileController = new ProfileController(profileService);

    @Test
    void getProfileShouldReturnProfile() {
        ProfileResponseDTO profile = ProfileResponseDTO.builder()
                .id("user-id")
                .username("john")
                .build();
        when(profileService.profile()).thenReturn(profile);

        var result = profileController.getProfile();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(profile);
    }
}
