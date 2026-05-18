package com.booksmith.app.service.impl;

import com.booksmith.app.dto.config.UserContextDTO;
import com.booksmith.app.model.User;
import com.booksmith.app.repository.AuthorRepository;
import com.booksmith.app.repository.ProfileRepository;
import com.booksmith.app.service.LocalStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfileServiceImplTest {

    private final ProfileRepository profileRepository = mock(ProfileRepository.class);
    private final UserContextDTO userContextDTO = new UserContextDTO();
    private final LocalStorageService localStorageService = mock(LocalStorageService.class);
    private final AuthorRepository authorRepository = mock(AuthorRepository.class);
    private final ProfileServiceImpl profileService = new ProfileServiceImpl(profileRepository, userContextDTO,localStorageService,authorRepository);

    @AfterEach
    void tearDown() {
        userContextDTO.clear();
    }

    @Test
    void profileShouldReturnCurrentUserProfile() {
        userContextDTO.setAuthenticatedUser(UserContextDTO.builder().userId("user-id").userName("john").build());
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
