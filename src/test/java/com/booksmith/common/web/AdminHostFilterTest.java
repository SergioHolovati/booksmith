package com.booksmith.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminHostFilterTest {

    @Test
    void adminRoutesOnlyAnswerOnTheBackofficeAddress() throws Exception {
        var filter = new AdminHostFilter("adminbooksmith.com.br");

        assertThat(status(filter, "booksmith.com.br", "/api/v1/admin/monitoring")).isEqualTo(404);
        assertThat(status(filter, "AdminBooksmith.com.br", "/api/v1/admin/monitoring")).isEqualTo(200);
        // O resto da API segue em qualquer endereço (o backoffice também faz login e lê o perfil)
        assertThat(status(filter, "booksmith.com.br", "/api/v1/books")).isEqualTo(200);
        assertThat(status(filter, "adminbooksmith.com.br", "/api/v1/auth/login")).isEqualTo(200);
    }

    @Test
    void withoutTheAddressNothingIsBlocked() throws Exception {
        assertThat(status(new AdminHostFilter(""), "localhost", "/api/v1/admin/monitoring")).isEqualTo(200);
    }

    private static int status(AdminHostFilter filter, String host, String path) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setServerName(host);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
