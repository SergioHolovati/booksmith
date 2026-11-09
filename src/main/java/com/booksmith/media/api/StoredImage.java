package com.booksmith.media.api;

public record StoredImage(String key, String url, String contentType, long bytes) {
}
