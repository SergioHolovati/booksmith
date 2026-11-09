package com.booksmith.media.api;

public enum ImageCategory {
    AVATAR("avatars"),
    BOOK_COVER("covers"),
    UNIVERSE_MAP("maps");

    private final String keyPrefix;

    ImageCategory(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String keyPrefix() {
        return keyPrefix;
    }
}
