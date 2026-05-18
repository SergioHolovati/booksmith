package com.booksmith.app.service;

public interface LocalStorageService {
    String saveAuthorAvatar(String authorId,String base64);
    void delete(String filePath);
}
