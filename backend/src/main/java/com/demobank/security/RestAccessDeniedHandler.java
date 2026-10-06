package com.demobank.security;

import com.demobank.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Answers authenticated-but-not-allowed requests with a JSON {@code 403}.
 *
 * <p>Reaching this handler is a security event worth recording: an
 * authenticated user asking for a resource their role does not grant is either
 * a bug in the client or somebody probing the API. The log line records who and
 * which path - never a token, never a payload.</p>
 *
 * <p>Note that ownership violations do <em>not</em> end up here: they are
 * handled as {@code 404} by {@code ResourceNotFoundException} so that the API
 * does not confirm the existence of other customers' resources.</p>
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String principal = (authentication != null) ? authentication.getName() : "anonymous";
        log.warn("Access denied for principal '{}' on {} {}", principal,
                request.getMethod(), request.getRequestURI());

        ApiError body = ApiError.of(
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "You are not allowed to perform this operation.",
                request.getRequestURI());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
