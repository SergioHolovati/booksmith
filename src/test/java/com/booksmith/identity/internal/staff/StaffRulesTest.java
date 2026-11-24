package com.booksmith.identity.internal.staff;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.AuthenticatedStaff;
import com.booksmith.identity.api.StaffRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class StaffRulesTest {

    private final StaffSecurity security = new StaffSecurity(mock(StaffTokens.class), mock(StaffStore.class),
            Clock.systemUTC());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlyCompanyEmailsCanBeStaff() {
        var properties = new StaffProperties("https://adminbooksmith.com.br", null, List.of(" Booksmith.com.br "));
        assertThat(properties.allowed("contact@booksmith.com.br")).isTrue();
        assertThat(properties.allowed("Ana@BOOKSMITH.com.br ")).isTrue();
        assertThat(properties.allowed("ana@gmail.com")).isFalse();
        // Subdomínio, domínio parecido e e-mail torto não passam
        assertThat(properties.allowed("ana@mail.booksmith.com.br")).isFalse();
        assertThat(properties.allowed("ana@booksmith.com.br.evil.com")).isFalse();
        assertThat(properties.allowed("booksmith.com.br")).isFalse();
        assertThat(properties.allowed(null)).isFalse();
        // Sem domínio configurado, ninguém entra
        assertThat(new StaffProperties(null, null, null).allowed("contact@booksmith.com.br")).isFalse();
    }

    @Test
    void eachRoleOnlyReachesItsSections() {
        signIn(StaffRole.MODERATOR);
        assertThat(security.require(StaffRole.MODERATOR).role()).isEqualTo(StaffRole.MODERATOR);
        assertThatThrownBy(() -> security.require(StaffRole.SUPPORT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("staff_role"));

        signIn(StaffRole.SUPPORT);
        assertThatThrownBy(() -> security.require(StaffRole.ADMIN)).isInstanceOf(ApiException.class);

        // O administrador passa em tudo
        signIn(StaffRole.ADMIN);
        assertThat(security.require(StaffRole.MODERATOR).role()).isEqualTo(StaffRole.ADMIN);
    }

    @Test
    void withoutAStaffTokenThereIsNobody() {
        assertThatThrownBy(security::get).isInstanceOf(ApiException.class);
        // Um cliente autenticado (outro tipo de principal) também não é da equipe
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("cliente", null, List.of()));
        assertThatThrownBy(security::get).isInstanceOf(ApiException.class);
    }

    @Test
    void onlyTheDomainOfAnOutsideEmailIsKept() {
        assertThat(StaffService.domain("Alguem@Gmail.com")).isEqualTo("gmail.com");
        assertThat(StaffService.domain("sem-arroba")).isEqualTo("?");
    }

    private static void signIn(StaffRole role) {
        var staff = new AuthenticatedStaff(UUID.randomUUID(), "x@booksmith.com.br", "X", role, "s1");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(staff, null, List.of()));
    }
}
