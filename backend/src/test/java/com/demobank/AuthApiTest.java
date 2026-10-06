package com.demobank;

import com.demobank.support.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authentication: the token endpoint and the gate in front of everything else.
 *
 * <p>The tests here are as much about what a failure <em>reveals</em> as about
 * the failure itself. An endpoint that answers "no such user" faster, or with a
 * different message, than "wrong password" hands an attacker a way to find out
 * who banks here - which is the first step of a targeted phishing campaign.</p>
 */
@DisplayName("Authentication")
class AuthApiTest extends BaseIntegrationTest {

    // -----------------------------------------------------------------------
    //  Successful authentication
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("valid credentials return a signed token and the user's identity")
    void loginWithValidCredentialsReturnsToken() throws Exception {
        mockMvc.perform(from(loginRequest(ALICE_EMAIL, TEST_PASSWORD), uniqueRemoteAddress()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andExpect(jsonPath("$.user.email").value(ALICE_EMAIL))
                .andExpect(jsonPath("$.user.fullName").value("Alice Rossi"))
                .andExpect(jsonPath("$.user.role").value("ROLE_CUSTOMER"));
    }

    @Test
    @DisplayName("the login response carries no password material")
    void loginResponseNeverExposesTheStoredHash() throws Exception {
        String body = mockMvc.perform(from(loginRequest(ALICE_EMAIL, TEST_PASSWORD),
                        uniqueRemoteAddress()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // A BCrypt hash is recognisable by its prefix. Neither it nor any field
        // that could carry it may appear in a response.
        assertThat(body).doesNotContain("$2a$", "$2b$", "$2y$");
        assertThat(body.toLowerCase()).doesNotContain("password");

        JsonNode json = objectMapper.readTree(body);
        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "accessToken", "tokenType", "expiresIn", "user");
        assertThat(fieldNames(json.get("user"))).containsExactlyInAnyOrder(
                "id", "email", "fullName", "role");
    }

    @Test
    @DisplayName("the email is matched case-insensitively")
    void loginIsCaseInsensitiveOnTheEmailAddress() throws Exception {
        mockMvc.perform(from(loginRequest("Alice@Test.Local", TEST_PASSWORD), uniqueRemoteAddress()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(ALICE_EMAIL));
    }

    @Test
    @DisplayName("a disabled account cannot sign in")
    void disabledAccountCannotLogIn() throws Exception {
        jdbcTemplate.update("update users set enabled = false where email = ?", BOB_EMAIL);

        mockMvc.perform(from(loginRequest(BOB_EMAIL, TEST_PASSWORD), uniqueRemoteAddress()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // -----------------------------------------------------------------------
    //  Refusals that must not leak who exists
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a wrong password and an unknown account are indistinguishable")
    void unknownAccountAndWrongPasswordProduceTheSameResponse() throws Exception {
        MvcResult wrongPassword = mockMvc.perform(from(
                        loginRequest(ALICE_EMAIL, "NotThePassword1!"), uniqueRemoteAddress()))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownAccount = mockMvc.perform(from(
                        loginRequest(UNKNOWN_EMAIL, "NotThePassword1!"), uniqueRemoteAddress()))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Everything an attacker can observe must match. `timestamp` is the only
        // field allowed to differ, which is why it is excluded here.
        assertThat(observable(wrongPassword)).isEqualTo(observable(unknownAccount));
    }

    @Test
    @DisplayName("the failure message names neither the account nor the reason")
    void failureMessageIsGeneric() throws Exception {
        mockMvc.perform(from(loginRequest(UNKNOWN_EMAIL, "NotThePassword1!"),
                        uniqueRemoteAddress()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                // A message saying "user not found" would enumerate customers.
                .andExpect(jsonPath("$.message").value(
                        "Invalid email or password."));
    }

    @Test
    @DisplayName("an unknown account still pays for a password verification")
    void unknownAccountStillPaysForThePasswordCheck() throws Exception {
        // This test exists because the timing guarantee has no other witness. It
        // is invisible in the response body, in the status and in every field -
        // which is precisely what makes it valuable, and precisely what let it
        // regress unnoticed: the comparison hash was a hand-written literal with
        // one character too many, so the encoder rejected it as malformed and
        // returned false without hashing anything at all. An unknown address came
        // back in microseconds while a wrong password took a quarter of a second,
        // and the endpoint was an enumeration oracle again.
        //
        // Warm-up first: the very first BCrypt call in a JVM also pays for class
        // loading and JIT, which would distort the measurement.
        mockMvc.perform(from(loginRequest(UNKNOWN_EMAIL, "NotThePassword1!"),
                        uniqueRemoteAddress()))
                .andExpect(status().isUnauthorized());

        long startedAt = System.nanoTime();
        mockMvc.perform(from(loginRequest(UNKNOWN_EMAIL, "NotThePassword1!"),
                        uniqueRemoteAddress()))
                .andExpect(status().isUnauthorized());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // A BCrypt comparison at cost 12 takes roughly 250 ms. The threshold is
        // an order of magnitude below that on purpose: this is not a benchmark,
        // it only has to tell "a real hash comparison happened" apart from "the
        // encoder gave up without doing any work", which takes about 0 ms.
        assertThat(elapsed)
                .as("an unknown email must cost one BCrypt comparison, otherwise the "
                        + "response time tells an attacker which addresses have accounts")
                .isGreaterThan(Duration.ofMillis(20));
    }

    // -----------------------------------------------------------------------
    //  Validation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a malformed email is rejected before any password check")
    void malformedEmailIsRejected() throws Exception {
        mockMvc.perform(from(loginRequest("not-an-email", TEST_PASSWORD), uniqueRemoteAddress()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));
    }

    @Test
    @DisplayName("a password shorter than the minimum is rejected")
    void tooShortPasswordIsRejected() throws Exception {
        mockMvc.perform(from(loginRequest(ALICE_EMAIL, "short"), uniqueRemoteAddress()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }

    @Test
    @DisplayName("an unknown field in the body is refused, not ignored")
    void unknownPropertyInTheBodyIsRefused() throws Exception {
        // Mass assignment: a client trying to smuggle a privilege into the
        // payload must be rejected outright rather than quietly ignored.
        mockMvc.perform(from(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","role":"ROLE_ADMIN"}"""
                                .formatted(ALICE_EMAIL, TEST_PASSWORD)),
                uniqueRemoteAddress()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("a body that is not JSON is rejected")
    void unparseableBodyIsRejected() throws Exception {
        mockMvc.perform(from(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"),
                uniqueRemoteAddress()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // -----------------------------------------------------------------------
    //  Brute-force protection
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("repeated failures lock the account out with HTTP 429")
    void repeatedFailuresAreThrottled() throws Exception {
        String address = uniqueRemoteAddress();

        // The configured limit is five failures inside the window.
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(from(loginRequest(ALICE_EMAIL, "WrongPassword1!"), address))
                    .andExpect(status().isUnauthorized());
        }

        // The sixth attempt is refused before the password is even examined...
        mockMvc.perform(from(loginRequest(ALICE_EMAIL, "WrongPassword1!"), address))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        // ...including when it carries the *correct* password. Otherwise the
        // throttle would be trivially bypassed by guessing right.
        mockMvc.perform(from(loginRequest(ALICE_EMAIL, TEST_PASSWORD), address))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("a successful sign-in clears the failure counter")
    void successfulLoginResetsTheCounter() throws Exception {
        String address = uniqueRemoteAddress();

        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(from(loginRequest(ALICE_EMAIL, "WrongPassword1!"), address))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(from(loginRequest(ALICE_EMAIL, TEST_PASSWORD), address))
                .andExpect(status().isOk());

        // Four more failures: still below the limit, so the user is not punished
        // for having mistyped before getting it right.
        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(from(loginRequest(ALICE_EMAIL, "WrongPassword1!"), address))
                    .andExpect(status().isUnauthorized());
        }
    }

    // -----------------------------------------------------------------------
    //  The gate in front of the API
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("protected endpoints refuse a request with no token")
    void protectedEndpointsRequireAToken() throws Exception {
        mockMvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/accounts/{id}", aliceCurrentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a tampered signature is refused")
    void tamperedTokenIsRefused() throws Exception {
        String token = loginAs(ALICE_EMAIL);

        // Flip the last character of the signature. The header and payload stay
        // perfectly valid: only the HMAC check can catch this.
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        mockMvc.perform(get("/api/accounts").header("Authorization", bearer(tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token that is not a JWT at all is refused")
    void garbageTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/accounts").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a scheme other than Bearer is refused")
    void basicSchemeIsRefused() throws Exception {
        String token = loginAs(ALICE_EMAIL);

        mockMvc.perform(get("/api/accounts").header("Authorization", "Basic " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a valid token grants access and identifies the caller")
    void validTokenIsAccepted() throws Exception {
        String token = loginAs(ALICE_EMAIL);

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(ALICE_EMAIL))
                .andExpect(jsonPath("$.fullName").value("Alice Rossi"));
    }

    // -----------------------------------------------------------------------
    //  Response hardening
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("API responses are marked no-store and carry the security headers")
    void apiResponsesAreHardened() throws Exception {
        mockMvc.perform(get("/api/accounts").header("Authorization", bearer(loginAs(ALICE_EMAIL))))
                // Financial data must not survive in a browser or proxy cache:
                // pressing "back" after signing out must not resurrect a
                // statement. The directive is matched as a substring because a
                // client is free to see more than one Cache-Control value.
                .andExpect(result -> assertThat(
                        String.join(",", result.getResponse().getHeaders("Cache-Control")))
                        .contains("no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"));
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(
            String email, String password) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(email, password));
    }

    /**
     * The parts of an error response an attacker can actually observe: status
     * and body. Response time is the third channel, and it is equalised in
     * {@code AuthService} rather than asserted here, because a timing assertion
     * would be flaky on a shared machine.
     */
    private String observable(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return result.getResponse().getStatus()
                + "|" + body.path("code").asText()
                + "|" + body.path("message").asText()
                + "|" + body.path("error").asText();
    }

    /** Top-level field names of a JSON object, so a test can pin the exact shape. */
    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
