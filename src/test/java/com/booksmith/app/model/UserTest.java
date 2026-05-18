package com.booksmith.app.model;

import com.booksmith.app.dto.request.RegisterRequestDTO;
import com.booksmith.app.enums.AuthProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void toEntityShouldMapRegisterRequest() {
        RegisterRequestDTO request = new RegisterRequestDTO(
                "John",
                "Doe",
                "john",
                "12345678900",
                "john@email.com",
                "Raw@12345",
                "+5519983592406"
        );

        User user = User.toEntity(request);

        assertThat(user.getFirstName()).isEqualTo("John");
        assertThat(user.getLastName()).isEqualTo("Doe");
        assertThat(user.getUsername()).isEqualTo("john");
        assertThat(user.getEmail()).isEqualTo("john@email.com");
        assertThat(user.getProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(user.isGoogleLinked()).isFalse();
        assertThat(user.isVerified()).isFalse();
    }

    @Test
    void toProfileShouldMapUser() {
        User user = User.builder()
                .id("user-id")
                .username("john")
                .firstName("John")
                .phone("+5519983592406")
                .email("john@email.com")
                .document("12345678900")
                .accountStatus("active")
                .build();

        var profile = User.toProfile(user);

        assertThat(profile.getId()).isEqualTo("user-id");
        assertThat(profile.getUsername()).isEqualTo("john");
        assertThat(profile.getName()).isEqualTo("John");
        assertThat(profile.getAccountStatus()).isEqualTo("active");
    }
}
