package com.booksmith.app.controller;

import com.booksmith.app.dto.request.AuthorRequestDTO;
import com.booksmith.app.dto.response.ProfileResponseDTO;
import com.booksmith.app.service.ProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/profile")
public class ProfileController {

    private final ProfileService profileService;
    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ResponseEntity<?> getProfile() {
        ProfileResponseDTO response = this.profileService.profile();
        return ResponseEntity.ok().body(response);
    }

    @PostMapping("/author")
    public ResponseEntity createAuthor(@RequestBody AuthorRequestDTO request){
        profileService.createAuthor(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/author")
    public ResponseEntity updateAuthor(@RequestBody AuthorRequestDTO request){
        profileService.updateAuthor(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
