package com.booksmith.media.api;

public enum ImageCategory {
    AVATAR("avatars"),
    BOOK_COVER("covers"),
    UNIVERSE_MAP("maps"),
    /** Biblioteca de imagens do universo: retratos, referências, lugares. */
    GALLERY("gallery");

    private final String keyPrefix;

    ImageCategory(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String keyPrefix() {
        return keyPrefix;
    }
}
