package com.booksmith.app.service.impl;

import com.booksmith.app.dto.config.UserContext;
import com.booksmith.app.dto.response.ProfileResponse;
import com.booksmith.app.model.User;
import com.booksmith.app.repository.ProfileRepository;
import com.booksmith.app.service.ProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ProfileServiceImpl implements ProfileService {
    private final ProfileRepository profileRepository;
    private final UserContext userContext;

    public ProfileServiceImpl(ProfileRepository profileRepository, UserContext userContext) {
        this.profileRepository = profileRepository;
        this.userContext = userContext;
    }

    @Override
    public ProfileResponse profile() {
        String userId = userContext.getUserId();
        log.info("Getting profile userId: {}",userId);
        User user = profileRepository.getReferenceById(userId);
        return User.toProfile(user);
    }
}
