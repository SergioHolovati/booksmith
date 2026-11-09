package com.booksmith.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param minimumAge idade mínima para criar conta (questão em aberto §32 do produto: 13 ou 16) */
@ConfigurationProperties("booksmith.identity")
public record IdentityProperties(int minimumAge) {
}
