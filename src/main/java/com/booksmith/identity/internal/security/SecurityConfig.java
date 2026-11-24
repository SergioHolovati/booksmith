package com.booksmith.identity.internal.security;

import com.booksmith.common.web.RequestTraceFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Três cadeias separadas:
 * <ol>
 *   <li>Login com Google: usa sessão HTTP só durante o redirecionamento (o "state" do OAuth2 fica nela).</li>
 *   <li>Equipe ({@code /api/v1/admin/**} e {@code /api/v1/staff/**}): só o token do backoffice ({@code StaffSecurity});
 *       o token de um cliente não autentica aqui.</li>
 *   <li>API dos clientes: stateless. A autenticação vem exclusivamente do token em cada requisição; nada é guardado em
 *       sessão, então um cookie nunca autentica e o logout vale de imediato.</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain oauth2LoginChain(HttpSecurity http,
                                         OAuth2SuccessHandler successHandler,
                                         SecurityProperties properties) throws Exception {
        return http
                .securityMatcher("/oauth2/**", "/login/oauth2/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                // "Vincular ao Google" no perfil: guarda o tíquete antes de ir ao Google
                .addFilterBefore(new GoogleLinkIntentFilter(),
                        org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter.class)
                .oauth2Login(oauth -> oauth
                        .successHandler(successHandler)
                        .failureHandler((request, response, exception) ->
                                response.sendRedirect(properties.frontendUrl() + "/login?error=google")))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain staffChain(HttpSecurity http,
                                   com.booksmith.identity.internal.staff.StaffSecurity staff,
                                   RateLimitProperties rateLimits,
                                   org.springframework.context.ApplicationEventPublisher events) throws Exception {
        return stateless(http.securityMatcher("/api/v1/admin/**", "/api/v1/staff/**"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/staff/auth/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new RateLimitingFilter(rateLimits, events), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(staff.filter(), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exception -> exception.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType("application/problem+json");
                    response.getWriter().write("""
                            {"status":401,"title":"Unauthorized","code":"unauthorized","detail":"Entre no backoffice."}""");
                }))
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain apiChain(HttpSecurity http,
                                 JwtTokenProvider tokenProvider,
                                 RateLimitProperties rateLimits,
                                 org.springframework.context.ApplicationEventPublisher events) throws Exception {
        return stateless(http)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new RateLimitingFilter(rateLimits, events), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exception -> exception.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType("application/problem+json");
                    response.getWriter().write("""
                            {"status":401,"title":"Unauthorized","code":"unauthorized",\
                            "detail":"Autenticação necessária."}""");
                }))
                .build();
    }

    /** O que as duas cadeias da API (equipe e clientes) têm em comum: só token, nada de sessão, cookie ou formulário. */
    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // O contexto vale só durante a requisição
                .securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                // A API só devolve JSON e arquivos: nada nela pode rodar script nem ser aberto dentro de outro site
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp
                        .policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.corsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", RequestTraceFilter.HEADER));
        config.setExposedHeaders(List.of("ETag", "Location", "Content-Disposition", RequestTraceFilter.HEADER));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
