package com.booksmith.app.service;

public interface LocalStorageService {
    String upload(String path,String authorId, String base64);
    void delete(String filePath);
}
