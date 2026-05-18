package com.booksmith.app.config.auth;

import com.booksmith.app.config.RateLimitingFilter;
import com.booksmith.app.dto.config.UserContextDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider tokenProvider;
    private final UserContextDTO userContextDTO;
    private final OAuth2SuccessHandler successHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        return http
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.IF_REQUIRED
                        )
                )

                .authorizeHttpRequests(authorize ->
                        authorize
                                .requestMatchers(
                                        "/auth/**",
                                        "/oauth2/**",
                                        "/login/oauth2/**"
                                ).permitAll()

                                .requestMatchers(
                                        "/actuator/health",
                                        "/actuator/info"
                                ).permitAll()

                                .anyRequest()
                                .authenticated()
                )

                .oauth2Login(oauth ->
                        oauth.successHandler(successHandler)
                )

                .addFilterBefore(
                        new RateLimitingFilter(),
                        UsernamePasswordAuthenticationFilter.class
                )

                .addFilterBefore(
                        new JwtAuthenticationFilter(
                                tokenProvider,
                                userContextDTO
                        ),
                        UsernamePasswordAuthenticationFilter.class
                )

                .exceptionHandling(exception ->
                        exception.authenticationEntryPoint(
                                (request, response, authException) -> {

                                    response.setStatus(401);
                                    response.setContentType(
                                            "application/json"
                                    );

                                    response.getWriter().write("""
                            {
                              "timestamp":"%s",
                              "status":401,
                              "messages":[
                                "Invalid token."
                              ]
                            }
                            """.formatted(
                                            java.time.LocalDateTime.now()
                                    ));
                                }
                        )
                )

                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
