package com.demobank;

import com.demobank.support.BaseIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Account statements: ordering, date filtering and the row cap.
 *
 * <p>The statement is seeded with three movements at known distances in the
 * past, which makes the expected result of every filter obvious at a glance
 * instead of something the reader has to recompute:</p>
 *
 * <pre>
 *   40 days ago   CREDIT   2000.00   "Opening deposit"
 *   10 days ago   DEBIT     900.00   "Rent"
 *    1 day  ago   DEBIT     100.00   "Supermarket"
 * </pre>
 *
 * <p>The dates are derived from "now" rather than written as literals, so the
 * suite does not start failing the day after it was written.</p>
 */
@DisplayName("Account statements")
class MovementApiTest extends BaseIntegrationTest {

    private static final String OPENING = "Opening deposit";
    private static final String RENT = "Rent";
    private static final String SUPERMARKET = "Supermarket";

    @BeforeEach
    void seedStatement() {
        insertMovement(aliceCurrentId, daysAgo(40), OPENING, "CREDIT", "2000.00", "2000.00");
        insertMovement(aliceCurrentId, daysAgo(10), RENT, "DEBIT", "900.00", "1100.00");
        insertMovement(aliceCurrentId, daysAgo(1), SUPERMARKET, "DEBIT", "100.00", "1000.00");
    }

    // -----------------------------------------------------------------------
    //  Ordering
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the newest movement comes first")
    void statementIsOrderedNewestFirst() throws Exception {
        mockMvc.perform(statement(null, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].description").value(SUPERMARKET))
                .andExpect(jsonPath("$[1].description").value(RENT))
                .andExpect(jsonPath("$[2].description").value(OPENING));
    }

    @Test
    @DisplayName("a statement line carries the direction, the amount and the running balance")
    void movementShapeIsComplete() throws Exception {
        mockMvc.perform(statement(null, null, null))
                .andExpect(status().isOk())
                // The amount is a positive magnitude; the direction is a
                // separate field, so the client never has to infer a sign
                // convention.
                .andExpect(jsonPath("$[0].type").value("DEBIT"))
                .andExpect(jsonPath("$[0].amount").value("100.00"))
                .andExpect(jsonPath("$[0].balanceAfter").value("1000.00"))
                .andExpect(jsonPath("$[0].executedAt").isNotEmpty())
                // Seeded lines have no originating transfer, and null fields are
                // omitted rather than sent as an explicit null.
                .andExpect(jsonPath("$[0].transferReference").doesNotExist());
    }

    @Test
    @DisplayName("amounts are decimal strings, not JSON numbers")
    void movementAmountsAreStrings() throws Exception {
        // A JSON number would become an IEEE-754 double in the browser, which
        // cannot hold every decimal exactly.
        mockMvc.perform(statement(null, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").isString())
                .andExpect(jsonPath("$[0].balanceAfter").isString());
    }

    // -----------------------------------------------------------------------
    //  Filtering
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a start date excludes everything before it")
    void filtersFromAStartDate() throws Exception {
        mockMvc.perform(statement(daysAgoDate(20), null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].description").value(SUPERMARKET))
                .andExpect(jsonPath("$[1].description").value(RENT));
    }

    @Test
    @DisplayName("an end date excludes everything after it")
    void filtersToAnEndDate() throws Exception {
        mockMvc.perform(statement(null, daysAgoDate(20), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].description").value(OPENING));
    }

    @Test
    @DisplayName("both bounds together select a window")
    void filtersWithBothBounds() throws Exception {
        mockMvc.perform(statement(daysAgoDate(45), daysAgoDate(35), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].description").value(OPENING));
    }

    @Test
    @DisplayName("both bounds are inclusive")
    void bothBoundsAreInclusive() throws Exception {
        // A single-day window. The query compares against a half-open interval
        // internally, so this is the test that catches an off-by-one in the
        // conversion from the user's calendar day to an instant.
        mockMvc.perform(statement(daysAgoDate(10), daysAgoDate(10), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].description").value(RENT));
    }

    @Test
    @DisplayName("a window with no movements returns an empty list, not an error")
    void emptyWindowReturnsEmptyList() throws Exception {
        mockMvc.perform(statement(daysAgoDate(200), daysAgoDate(100), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("a reversed date range is refused")
    void reversedRangeIsRefused() throws Exception {
        mockMvc.perform(statement(daysAgoDate(1), daysAgoDate(30), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    @DisplayName("a date that is not ISO-8601 is refused")
    void malformedDateIsRefused() throws Exception {
        mockMvc.perform(get("/api/accounts/{id}/movements", aliceCurrentId)
                        .param("from", "31/01/2026")
                        .header("Authorization", bearer(loginAs(ALICE_EMAIL))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // -----------------------------------------------------------------------
    //  Row cap
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the limit keeps the newest rows")
    void limitKeepsTheNewestRows() throws Exception {
        mockMvc.perform(statement(null, null, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].description").value(SUPERMARKET));
    }

    @Test
    @DisplayName("a limit larger than the statement returns everything")
    void limitLargerThanTheStatementIsHarmless() throws Exception {
        mockMvc.perform(statement(null, null, 500))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    // -----------------------------------------------------------------------
    //  Access
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a statement requires a token")
    void statementRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/accounts/{id}/movements", aliceCurrentId))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private MockHttpServletRequestBuilder statement(
            LocalDate from, LocalDate to, Integer limit) throws Exception {

        var request = get("/api/accounts/{id}/movements", aliceCurrentId)
                .header("Authorization", bearer(loginAs(ALICE_EMAIL)));

        if (from != null) {
            request = request.param("from", from.toString());
        }
        if (to != null) {
            request = request.param("to", to.toString());
        }
        if (limit != null) {
            request = request.param("limit", limit.toString());
        }
        return request;
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    private static LocalDate daysAgoDate(int days) {
        return LocalDate.now(ZoneOffset.UTC).minusDays(days);
    }
}
