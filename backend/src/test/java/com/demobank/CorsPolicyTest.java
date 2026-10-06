package com.demobank;

import com.demobank.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The CORS policy.
 *
 * <p>These tests exist because of a failure that no other test in this suite
 * could have caught. Browsers attach an {@code Origin} header to <em>every</em>
 * POST, including same-origin ones. In the Docker deployment nginx serves the
 * SPA and proxies the API on a single port, so every login and every transfer
 * arrives as {@code Origin: http://localhost:8080} - an origin that was not in
 * an allow-list written for the Angular dev server. The API answered
 * {@code 403 Invalid CORS request} to all of them: the application was
 * completely unusable from a browser, and the whole suite stayed green, because
 * no test sent an {@code Origin} header and neither does curl.</p>
 *
 * <p>The tests below therefore do two things: they pin the same-origin case
 * that must keep working, and they confirm the allow-list still refuses what it
 * is there to refuse.</p>
 */
@DisplayName("CORS policy")
class CorsPolicyTest extends BaseIntegrationTest {

    private static final String DOCKER_ORIGIN = "http://localhost:8080";
    private static final String DEV_SERVER_ORIGIN = "http://localhost:4200";
    private static final String FOREIGN_ORIGIN = "http://evil.example";

    private static String loginBody() {
        return """
                {"email":"%s","password":"%s"}""".formatted(ALICE_EMAIL, TEST_PASSWORD);
    }

    // -----------------------------------------------------------------------
    //  Same-origin: the case that must never be refused
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a browser posting to its own origin is not treated as cross-origin")
    void sameOriginPostIsAccepted() throws Exception {
        mockMvc.perform(from(post("/api/auth/login")
                        .header(HttpHeaders.HOST, "localhost:8080")
                        .header(HttpHeaders.ORIGIN, DOCKER_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()), uniqueRemoteAddress()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the same-origin check works behind the proxy, where nginx supplies the host")
    void sameOriginIsRecognisedThroughTheProxy() throws Exception {
        // This is the shape the request really has in Docker: nginx rewrites
        // Host without the port and puts the client's original host, port
        // included, in X-Forwarded-Host.
        mockMvc.perform(from(post("/api/auth/login")
                        .header(HttpHeaders.HOST, "localhost")
                        .header("X-Forwarded-Host", "localhost:8080")
                        .header("X-Forwarded-Proto", "http")
                        .header(HttpHeaders.ORIGIN, DOCKER_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()), uniqueRemoteAddress()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a same-origin read is accepted too")
    void sameOriginGetIsAccepted() throws Exception {
        String token = loginAs(ALICE_EMAIL);

        mockMvc.perform(get("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header(HttpHeaders.HOST, "localhost:8080")
                        .header(HttpHeaders.ORIGIN, DOCKER_ORIGIN))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    //  The allow-list still does its job
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the configured development origin is still allowed")
    void configuredOriginIsStillAllowed() throws Exception {
        mockMvc.perform(from(post("/api/auth/login")
                        .header(HttpHeaders.HOST, "localhost:8080")
                        .header(HttpHeaders.ORIGIN, DEV_SERVER_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()), uniqueRemoteAddress()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("another site cannot post to the API")
    void foreignOriginIsRefusedOnPost() throws Exception {
        mockMvc.perform(from(post("/api/auth/login")
                        .header(HttpHeaders.HOST, "localhost:8080")
                        .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()), uniqueRemoteAddress()))
                .andExpect(status().isForbidden())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("Invalid CORS request"));
    }

    @Test
    @DisplayName("another site cannot read the API either")
    void foreignOriginIsRefusedOnGet() throws Exception {
        String token = loginAs(ALICE_EMAIL);

        mockMvc.perform(get("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header(HttpHeaders.HOST, "localhost:8080")
                        .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a preflight from another site is refused")
    void foreignOriginIsRefusedOnPreflight() throws Exception {
        mockMvc.perform(options("/api/transfers")
                        .header(HttpHeaders.HOST, "localhost:8080")
                        .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a preflight from the application's own origin is answered")
    void sameOriginPreflightIsAnswered() throws Exception {
        mockMvc.perform(options("/api/transfers")
                        .header(HttpHeaders.HOST, "localhost:8080")
                        .header(HttpHeaders.ORIGIN, DOCKER_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk());
    }

    /**
     * A guard against the classic way this kind of check is written wrongly:
     * matching the allowed origin as a prefix or a suffix. A page served from
     * {@code localhost:8080.evil.example} is a different origin, and the
     * browser would treat it as one.
     */
    @Test
    @DisplayName("an origin that merely starts with the allowed one is refused")
    void lookalikeOriginIsRefused() throws Exception {
        mockMvc.perform(from(post("/api/auth/login")
                        .header(HttpHeaders.HOST, "localhost:8080")
                        .header(HttpHeaders.ORIGIN, "http://localhost:8080.evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()), uniqueRemoteAddress()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a request with no Origin header is untouched")
    void absentOriginIsNotSubjectToCors() throws Exception {
        // curl, and any other non-browser client, sends no Origin. CORS has
        // nothing to say about it, and it must not be refused on that basis.
        mockMvc.perform(from(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()), uniqueRemoteAddress()))
                .andExpect(status().isOk());
    }
}
