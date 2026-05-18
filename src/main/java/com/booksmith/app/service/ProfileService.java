package com.booksmith.app.service;

import com.booksmith.app.dto.request.AuthorRequestDTO;
import com.booksmith.app.dto.response.ProfileAuthorResponseDTO;
import com.booksmith.app.dto.response.ProfileResponseDTO;

public interface ProfileService {
    ProfileResponseDTO profile();
    void createAuthor(AuthorRequestDTO authorRequestDTO);
    void  updateAuthor(AuthorRequestDTO authorRequestDTO);
}
