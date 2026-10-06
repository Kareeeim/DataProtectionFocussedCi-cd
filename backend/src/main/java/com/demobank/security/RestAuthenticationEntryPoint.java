package com.demobank.security;

import com.demobank.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Answers unauthenticated requests with a JSON {@code 401} instead of Spring
 * Security's default HTML page or a redirect to a login form.
 *
 * <p>A REST API must never redirect: a client following a {@code 302} to an HTML
 * login page turns a clean authentication failure into a confusing parse error.
 * It must also not describe <em>why</em> the request was rejected - "missing
 * token" and "expired token" are useful to an attacker probing the
 * implementation and useless to a legitimate client, which only has to log in
 * again.</p>
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ApiError body = ApiError.of(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED",
                "Authentication is required to access this resource.",
                request.getRequestURI());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Never let a proxy or the browser cache an authentication failure.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
