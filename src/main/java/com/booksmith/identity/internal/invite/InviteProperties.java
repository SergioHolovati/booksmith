package com.booksmith.identity.internal.invite;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param required beta fechado: criar conta exige código de convite */
@ConfigurationProperties("booksmith.invites")
public record InviteProperties(boolean required) {
}
