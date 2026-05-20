package com.booksmith.app.service;

import com.booksmith.app.dto.request.BookRequestDTO;
import com.booksmith.app.dto.response.BookResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BookService {
    Page<BookResponseDTO> booksByAuthor(Pageable pageable);
    BookResponseDTO create(BookRequestDTO bookRequestDTO);
    BookResponseDTO update(String id, BookRequestDTO bookRequestDTO) throws ClassNotFoundException;
    void delete(String id) throws ClassNotFoundException;
}
