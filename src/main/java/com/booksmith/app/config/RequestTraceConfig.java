package com.booksmith.app.config;

import com.booksmith.app.dto.config.UserContextDTO;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class RequestTraceConfig {

    @Bean
    public FilterRegistrationBean<RequestTraceFilter> requestTraceFilter(UserContextDTO userContextDTO) {
        FilterRegistrationBean<RequestTraceFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestTraceFilter(userContextDTO));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
