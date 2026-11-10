package com.booksmith.identity.internal.user;

import com.booksmith.identity.api.UserDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class JpaUserDirectory implements UserDirectory {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> email(UUID userId) {
        return userRepository.findById(userId).map(User::getEmail);
    }
}
