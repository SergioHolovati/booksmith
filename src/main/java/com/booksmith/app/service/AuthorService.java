package com.booksmith.app.service;

import com.booksmith.app.model.Author;

public interface AuthorService {
    Author findAuthorById(String id);
}
