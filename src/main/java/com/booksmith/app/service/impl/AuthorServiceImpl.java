package com.booksmith.app.service.impl;

import com.booksmith.app.model.Author;
import com.booksmith.app.repository.AuthorRepository;
import com.booksmith.app.service.AuthorService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AuthorServiceImpl implements AuthorService {
    private AuthorRepository authorRepository;


    public AuthorServiceImpl(AuthorRepository authorRepository) {
        this.authorRepository = authorRepository;
    }

    @Override
    public Author findAuthorById(String id) {
        return authorRepository.findById(id).orElseThrow(
                ()-> new UsernameNotFoundException("Author not found"));
    }
}
