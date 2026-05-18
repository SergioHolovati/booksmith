package com.booksmith.app.service.impl;

import com.booksmith.app.dto.config.UserContextDTO;
import com.booksmith.app.dto.response.ProfileResponseDTO;
import com.booksmith.app.model.User;
import com.booksmith.app.repository.ProfileRepository;
import com.booksmith.app.service.ProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ProfileServiceImpl implements ProfileService {
    private final ProfileRepository profileRepository;
    private final UserContextDTO userContextDTO;

    public ProfileServiceImpl(ProfileRepository profileRepository, UserContextDTO userContextDTO) {
        this.profileRepository = profileRepository;
        this.userContextDTO = userContextDTO;
    }

    @Override
    public ProfileResponseDTO profile() {
        String userId = userContextDTO.getUserId();
        log.info("Getting profile userId: {}",userId);
        User user = profileRepository.getReferenceById(userId);
        return User.toProfile(user);
    }
}
