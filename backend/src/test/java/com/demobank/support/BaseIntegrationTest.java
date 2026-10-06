package com.demobank.support;

import com.demobank.security.LoginAttemptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared fixture for the API integration tests.
 *
 * <h2>How these tests are put together</h2>
 * <p>They are full-stack and black-box: a real Spring context, the real security
 * filter chain, the real JWT verification and a real database. Requests go in
 * over HTTP and assertions are made on the response <em>and</em> on what ended
 * up in the database. Nothing is stubbed, because the properties this suite
 * exists to protect - that one customer cannot read another's data, and that a
 * failed transfer leaves no trace - are properties of the whole assembly, not of
 * any single class.</p>
 *
 * <h2>Test isolation</h2>
 * <p>Each test starts from an empty database that is then seeded with the same
 * fixture, so tests share no state and can run in any order. Two pieces of state
 * live outside the database and are handled explicitly:</p>
 * <ul>
 *   <li>the login throttle, which is an in-memory bean that survives between
 *       tests in the same context - cleared in {@link #resetState()};</li>
 *   <li>the source address of each request, which is the key the per-IP throttle
 *       uses - every test gets its own address from {@link #uniqueRemoteAddress()}
 *       so a failed login in one test can never affect another.</li>
 * </ul>
 *
 * <p>Tests are deliberately <strong>not</strong> annotated with
 * {@code @Transactional}: the transfer atomicity tests have to observe what was
 * actually committed, and a test-managed transaction that always rolls back
 * would hide exactly the behaviour under examination.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    /** Password of every seeded account. Never a real credential. */
    protected static final String TEST_PASSWORD = "Demo1234!";

    protected static final String ALICE_EMAIL = "alice@test.local";
    protected static final String BOB_EMAIL = "bob@test.local";

    /**
     * An address that is deliberately absent from the database. Used by the
     * tests that check a login failure reveals nothing about whether an account
     * exists.
     */
    protected static final String UNKNOWN_EMAIL = "nobody@test.local";

    protected static final String ALICE_CURRENT_IBAN = "IT60X0542811101000000123456";
    protected static final String ALICE_SAVINGS_IBAN = "IT60X0542811101000000123457";
    protected static final String ALICE_USD_IBAN = "IT60X0542811101000000123458";
    protected static final String ALICE_BILLS_IBAN = "IT60X0542811101000000123460";
    protected static final String BOB_CURRENT_IBAN = "IT60X0542811101000000123459";

    /**
     * BCrypt at cost 12 takes roughly a quarter of a second. Hashing the fixture
     * password once per JVM instead of once per test keeps that cost from being
     * paid thirty times over.
     */
    private static String cachedPasswordHash;

    /** Source of distinct, documentation-range addresses (RFC 5737, TEST-NET-1). */
    private static final AtomicInteger ADDRESS_COUNTER = new AtomicInteger();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected LoginAttemptService loginAttempts;

    @Autowired
    protected ObjectMapper objectMapper;

    /** Primary keys of the seeded rows, resolved after insertion. */
    protected long aliceId;
    protected long bobId;
    protected long aliceCurrentId;
    protected long aliceSavingsId;
    protected long aliceUsdId;
    protected long aliceBillsId;
    protected long bobCurrentId;

    // -----------------------------------------------------------------------
    //  Fixture
    // -----------------------------------------------------------------------

    @BeforeEach
    void resetState() {
        clearDatabase();
        seedDatabase();

        // The throttle is in-memory and shared by every test in this context.
        // Clearing the keys this suite uses keeps the tests order-independent.
        loginAttempts.reset(LoginAttemptService.userKey(ALICE_EMAIL));
        loginAttempts.reset(LoginAttemptService.userKey(BOB_EMAIL));
        loginAttempts.reset(LoginAttemptService.userKey(UNKNOWN_EMAIL));
    }

    private void clearDatabase() {
        // Children first: every foreign key in the schema is ON DELETE RESTRICT,
        // so the order below is the only one that works.
        jdbcTemplate.execute("delete from movements");
        jdbcTemplate.execute("delete from transfers");
        jdbcTemplate.execute("delete from accounts");
        jdbcTemplate.execute("delete from users");
    }

    private void seedDatabase() {
        aliceId = insertUser(ALICE_EMAIL, "Alice Rossi", true);
        bobId = insertUser(BOB_EMAIL, "Bob Neri", true);

        aliceCurrentId = insertAccount(aliceId, ALICE_CURRENT_IBAN, "EUR", "1000.00");
        aliceSavingsId = insertAccount(aliceId, ALICE_SAVINGS_IBAN, "EUR", "250.50");
        aliceUsdId = insertAccount(aliceId, ALICE_USD_IBAN, "USD", "100.00");
        // A second EUR destination, so a test can aim two transfers at two
        // different accounts and still cross the currency check.
        aliceBillsId = insertAccount(aliceId, ALICE_BILLS_IBAN, "EUR", "0.00");
        bobCurrentId = insertAccount(bobId, BOB_CURRENT_IBAN, "EUR", "500.00");
    }

    protected long insertUser(String email, String fullName, boolean enabled) {
        jdbcTemplate.update("""
                insert into users (email, password_hash, full_name, role, enabled,
                                   created_at, updated_at)
                values (?, ?, ?, 'ROLE_CUSTOMER', ?, ?, ?)
                """,
                email, passwordHash(), fullName, enabled, now(), now());

        return jdbcTemplate.queryForObject(
                "select id from users where email = ?", Long.class, email);
    }

    protected long insertAccount(long ownerId, String accountNumber, String currency,
                                 String balance) {
        jdbcTemplate.update("""
                insert into accounts (account_number, owner_id, currency, balance, version,
                                      created_at, updated_at)
                values (?, ?, ?, ?, 0, ?, ?)
                """,
                accountNumber, ownerId, currency, new BigDecimal(balance), now(), now());

        return jdbcTemplate.queryForObject(
                "select id from accounts where account_number = ?", Long.class, accountNumber);
    }

    /** Inserts a statement line directly, so a test can control the dates. */
    protected void insertMovement(long accountId, Instant executedAt, String description,
                                  String type, String amount, String balanceAfter) {
        jdbcTemplate.update("""
                insert into movements (account_id, executed_at, description, type,
                                       amount, balance_after, transfer_id)
                values (?, ?, ?, ?, ?, ?, null)
                """,
                accountId,
                OffsetDateTime.ofInstant(executedAt, ZoneOffset.UTC),
                description, type, new BigDecimal(amount), new BigDecimal(balanceAfter));
    }

    private String passwordHash() {
        if (cachedPasswordHash == null) {
            cachedPasswordHash = passwordEncoder.encode(TEST_PASSWORD);
        }
        return cachedPasswordHash;
    }

    /** A timestamp suitable for the {@code created_at} / {@code updated_at} columns. */
    protected static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    // -----------------------------------------------------------------------
    //  HTTP helpers
    // -----------------------------------------------------------------------

    /**
     * A source address unique to one test.
     *
     * <p>Without this, every MockMvc request would arrive from {@code 127.0.0.1}
     * and a test that deliberately exhausts the login throttle would lock out the
     * tests that merely sign in. The addresses are taken from TEST-NET-1
     * ({@code 192.0.2.0/24}), the range reserved for documentation, so they can
     * never collide with a real host.</p>
     */
    protected static String uniqueRemoteAddress() {
        return "192.0.2." + (ADDRESS_COUNTER.incrementAndGet() % 250 + 1);
    }

    /** Makes the request appear to come from the given address. */
    protected static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder,
                                                       String remoteAddress) {
        return builder.with(request -> {
            request.setRemoteAddr(remoteAddress);
            return request;
        });
    }

    /** {@code Authorization: Bearer <token>} header value for a token. */
    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    /**
     * Signs in through the real endpoint and returns the access token.
     *
     * <p>Deliberately not a shortcut that mints a token directly: going through
     * {@code POST /api/auth/login} means every test that needs a session also
     * exercises the password verification and the token issuance. A test that
     * bypasses authentication to test authorisation would be proving less than
     * it appears to.</p>
     */
    protected String loginAs(String email) throws Exception {
        String body = mockMvc.perform(from(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, TEST_PASSWORD)),
                uniqueRemoteAddress()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("accessToken").asText();
    }

    // -----------------------------------------------------------------------
    //  Database assertions
    // -----------------------------------------------------------------------

    /** Current balance of an account, read straight from the row. */
    protected BigDecimal balanceOf(long accountId) {
        return jdbcTemplate.queryForObject(
                "select balance from accounts where id = ?", BigDecimal.class, accountId);
    }

    protected int countMovements(long accountId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from movements where account_id = ?", Integer.class, accountId);
        return count == null ? 0 : count;
    }

    protected int countAllMovements() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from movements", Integer.class);
        return count == null ? 0 : count;
    }

    protected int countTransfers() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from transfers", Integer.class);
        return count == null ? 0 : count;
    }

    /** Rows of one account's statement, oldest first, as {@code type|amount|balance_after}. */
    protected List<Map<String, Object>> movementsOf(long accountId) {
        return jdbcTemplate.queryForList("""
                select type, amount, balance_after, description, executed_at
                from movements where account_id = ? order by executed_at asc, id asc
                """, accountId);
    }
}
