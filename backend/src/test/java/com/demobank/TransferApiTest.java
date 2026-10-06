package com.demobank;

import com.demobank.support.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transfers: the happy path, every refusal, and the state left behind.
 *
 * <h2>The assertion that matters most</h2>
 * <p>Almost every test in this class ends with
 * {@link #assertUnchanged(Snapshot)}. A refusal that returns the right HTTP
 * status but has already debited the source account is far worse than one that
 * returns the wrong status: the customer has lost money and the statement does
 * not explain where it went. Checking the response alone would not catch that,
 * so each refusal is checked against a snapshot of the whole relevant state -
 * both balances, the transfer count and the movement count - taken before the
 * request was made.</p>
 */
@DisplayName("Transfers")
class TransferApiTest extends BaseIntegrationTest {

    // -----------------------------------------------------------------------
    //  The happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a valid transfer moves the money and records it on both statements")
    void validTransferMovesMoneyAndRecordsBothLegs() throws Exception {
        MvcResult result = transfer(ALICE_EMAIL, aliceCurrentId, aliceSavingsId,
                "100.00", "Rent share")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").isNotEmpty())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.description").value("Rent share"))
                // Both accounts come back with their new balance, so the UI can
                // refresh without a second round trip.
                .andExpect(jsonPath("$.sourceAccount.balance").value("900.00"))
                .andExpect(jsonPath("$.destinationAccount.balance").value("350.50"))
                .andReturn();

        String reference = read(result).path("reference").asText();

        // --- the money actually moved ---------------------------------------
        assertThat(balanceOf(aliceCurrentId)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(aliceSavingsId)).isEqualByComparingTo("350.50");

        // --- one audit record, two statement lines --------------------------
        assertThat(countTransfers()).isEqualTo(1);
        assertThat(countAllMovements()).isEqualTo(2);

        long transferId = transferIdOf(reference);

        List<Map<String, Object>> movements = jdbcTemplate.queryForList("""
                select m.type, m.amount, m.balance_after, a.account_number
                from movements m join accounts a on a.id = m.account_id
                where m.transfer_id = ?
                """, transferId);

        assertThat(movements).hasSize(2);
        assertThat(movements)
                .anySatisfy(leg -> {
                    assertThat(leg.get("type")).isEqualTo("DEBIT");
                    assertThat((String) leg.get("account_number")).isEqualTo(ALICE_CURRENT_IBAN);
                })
                .anySatisfy(leg -> {
                    assertThat(leg.get("type")).isEqualTo("CREDIT");
                    assertThat((String) leg.get("account_number")).isEqualTo(ALICE_SAVINGS_IBAN);
                });
    }

    @Test
    @DisplayName("both statement lines carry the same instant")
    void bothLegsShareOneTimestamp() throws Exception {
        transferReference(ALICE_EMAIL, aliceCurrentId, aliceSavingsId, "100.00", null);

        // A transfer is one event. If the two legs were stamped independently,
        // a statement sorted by time could show the credit before the debit,
        // which is impossible in reality and confusing to read.
        Integer distinctInstants = jdbcTemplate.queryForObject(
                "select count(distinct executed_at) from movements", Integer.class);

        assertThat(distinctInstants).isEqualTo(1);
    }

    @Test
    @DisplayName("the transfer is attributed to the authenticated caller")
    void transferIsAttributedToTheCaller() throws Exception {
        String reference = transferReference(ALICE_EMAIL, aliceCurrentId, aliceSavingsId, "10.00", null);

        Long initiatedBy = jdbcTemplate.queryForObject(
                "select initiated_by from transfers where reference = ?", Long.class, reference);

        // The initiator comes from the token, never from the request body.
        assertThat(initiatedBy).isEqualTo(aliceId);
    }

    @Test
    @DisplayName("the total held by the customer is unchanged")
    void transferConservesTheTotal() throws Exception {
        BigDecimal before = balanceOf(aliceCurrentId).add(balanceOf(aliceSavingsId));

        transferReference(ALICE_EMAIL, aliceCurrentId, aliceSavingsId, "123.45", "Conservation");

        assertThat(balanceOf(aliceCurrentId).add(balanceOf(aliceSavingsId)))
                .isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("the balance can be spent down to exactly zero")
    void spendingTheWholeBalanceIsAllowed() throws Exception {
        // The boundary of the insufficient-funds rule: equal is allowed, one
        // cent more is not.
        transfer(ALICE_EMAIL, aliceCurrentId, aliceSavingsId, "1000.00", "Everything")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceAccount.balance").value("0.00"));

        assertThat(balanceOf(aliceCurrentId)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("the amount may be sent as a JSON string, as the web client does")
    void amountMayBeSentAsAString() throws Exception {
        // The Angular client posts the amount as a string so that the value
        // never passes through a JavaScript float. The API accepts both forms.
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", bearer(loginAs(ALICE_EMAIL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":%d,"destinationAccountId":%d,
                                 "amount":"100.00","description":"As a string"}"""
                                .formatted(aliceCurrentId, aliceSavingsId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("100.00"));

        assertThat(balanceOf(aliceCurrentId)).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("the description is optional")
    void descriptionIsOptional() throws Exception {
        transfer(ALICE_EMAIL, aliceCurrentId, aliceSavingsId, "25.00", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").doesNotExist());

        // Without a description the statement lines fall back to just naming
        // the counterpart account.
        String outgoing = jdbcTemplate.queryForObject("""
                select description from movements where account_id = ? and type = 'DEBIT'
                """, String.class, aliceCurrentId);

        assertThat(outgoing).isEqualTo("Transfer to " + ALICE_SAVINGS_IBAN);
    }

    @Test
    @DisplayName("the statement lines name the counterpart account")
    void statementLinesNameTheCounterpart() throws Exception {
        transferReference(ALICE_EMAIL, aliceCurrentId, aliceSavingsId, "25.00", "Dinner");

        String outgoing = jdbcTemplate.queryForObject("""
                select description from movements where account_id = ? and type = 'DEBIT'
                """, String.class, aliceCurrentId);
        String incoming = jdbcTemplate.queryForObject("""
                select description from movements where account_id = ? and type = 'CREDIT'
                """, String.class, aliceSavingsId);

        assertThat(outgoing).isEqualTo("Transfer to " + ALICE_SAVINGS_IBAN + " - Dinner");
        assertThat(incoming).isEqualTo("Transfer from " + ALICE_CURRENT_IBAN + " - Dinner");
    }

    @Test
    @DisplayName("the operation is identified by a reference, not by a sequential id")
    void referenceIsNotASequentialId() throws Exception {
        String reference = transferReference(ALICE_EMAIL, aliceCurrentId, aliceSavingsId, "5.00", null);

        // A sequential identifier would leak how much business the bank does
        // and invite enumeration. A UUID leaks neither.
        assertThat(UUID.fromString(reference)).isNotNull();
        assertThat(reference).doesNotMatch("\\d+");
    }

    // -----------------------------------------------------------------------
    //  Refusals - each one must leave the database untouched
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a transfer larger than the balance is refused and changes nothing")
    void insufficientFundsIsRefused() throws Exception {
        Snapshot before = snapshot();

        transfer(ALICE_EMAIL, aliceCurrentId, aliceSavingsId, "1000.01", "Too much")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertUnchanged(before);
    }

    @Test
    @DisplayName("a transfer from an empty account is refused")
    void transferFromAnEmptyAccountIsRefused() throws Exception {
        Snapshot before = snapshot();

        // The bills account starts at 0.00.
        transfer(ALICE_EMAIL, aliceBillsId, aliceSavingsId, "0.01", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertUnchanged(before);
    }

    @Test
    @DisplayName("a transfer to the same account is refused")
    void transferToTheSameAccountIsRefused() throws Exception {
        Snapshot before = snapshot();

        transfer(ALICE_EMAIL, aliceCurrentId, aliceCurrentId, "10.00", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));

        assertUnchanged(before);
        // In particular the balance is not left credited or debited by the
        // amount, which is what a naive implementation would do.
        assertThat(balanceOf(aliceCurrentId)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("a transfer out of another customer's account is refused")
    void transferFromAForeignAccountIsRefused() throws Exception {
        Snapshot before = snapshot();

        // Alice holds a valid token and names Bob's account as the source.
        transfer(ALICE_EMAIL, bobCurrentId, aliceSavingsId, "10.00", "Not mine")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertUnchanged(before);
        // Bob's money is untouched, which is the whole point.
        assertThat(balanceOf(bobCurrentId)).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("a transfer into another customer's account is refused")
    void transferToAForeignAccountIsRefused() throws Exception {
        Snapshot before = snapshot();

        transfer(ALICE_EMAIL, aliceCurrentId, bobCurrentId, "10.00", "Not yours")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertUnchanged(before);
        assertThat(balanceOf(bobCurrentId)).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("a foreign account and a nonexistent one are refused identically")
    void foreignAndMissingAccountsAreIndistinguishable() throws Exception {
        String token = loginAs(ALICE_EMAIL);

        String foreign = mockMvc.perform(postTransfer(token,
                        transferBody(bobCurrentId, aliceSavingsId, "10.00", null)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        String missing = mockMvc.perform(postTransfer(token,
                        transferBody(999_999L, aliceSavingsId, "10.00", null)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(read(foreign).path("code")).isEqualTo(read(missing).path("code"));
        assertThat(read(foreign).path("message")).isEqualTo(read(missing).path("message"));
    }

    @Test
    @DisplayName("a transfer between different currencies is refused rather than approximated")
    void currencyMismatchIsRefused() throws Exception {
        Snapshot before = snapshot();

        transfer(ALICE_EMAIL, aliceCurrentId, aliceUsdId, "10.00", "To USD")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));

        assertUnchanged(before);
    }

    @Test
    @DisplayName("a transfer requires a token")
    void transferRequiresAToken() throws Exception {
        Snapshot before = snapshot();

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(aliceCurrentId, aliceSavingsId, "10.00", null)))
                .andExpect(status().isUnauthorized());

        assertUnchanged(before);
    }

    // -----------------------------------------------------------------------
    //  Validation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a zero amount is refused")
    void zeroAmountIsRefused() throws Exception {
        assertRejectedAsInvalid("0.00", "amount");
    }

    @Test
    @DisplayName("a negative amount is refused")
    void negativeAmountIsRefused() throws Exception {
        // A negative amount would reverse the direction of the transfer while
        // still passing a naive "amount is not zero" check.
        assertRejectedAsInvalid("-10.00", "amount");
    }

    @Test
    @DisplayName("an amount with more than two decimals is refused, not rounded")
    void subCentAmountIsRefused() throws Exception {
        // 10.005 must not silently become 10.01 or 10.00: inventing or
        // destroying a fraction of a cent is exactly what makes financial
        // software untrustworthy.
        assertRejectedAsInvalid("10.005", "amount");
    }

    @Test
    @DisplayName("an amount too large for the column is refused by validation, not by the database")
    void amountTooLargeForTheColumnIsRefused() throws Exception {
        assertRejectedAsInvalid("100000000000000000.00", "amount");
    }

    @Test
    @DisplayName("a missing amount is refused")
    void missingAmountIsRefused() throws Exception {
        Snapshot before = snapshot();

        mockMvc.perform(postTransfer(loginAs(ALICE_EMAIL), """
                        {"sourceAccountId":%d,"destinationAccountId":%d}"""
                        .formatted(aliceCurrentId, aliceSavingsId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"));

        assertUnchanged(before);
    }

    @Test
    @DisplayName("a non-positive account id is refused")
    void nonPositiveAccountIdIsRefused() throws Exception {
        Snapshot before = snapshot();

        mockMvc.perform(postTransfer(loginAs(ALICE_EMAIL),
                        transferBody(0L, aliceSavingsId, "10.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sourceAccountId"));

        assertUnchanged(before);
    }

    @Test
    @DisplayName("a description longer than the column is refused, not truncated")
    void overlongDescriptionIsRefused() throws Exception {
        Snapshot before = snapshot();

        mockMvc.perform(postTransfer(loginAs(ALICE_EMAIL),
                        transferBody(aliceCurrentId, aliceSavingsId, "10.00", "x".repeat(141))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("description"));

        assertUnchanged(before);
    }

    @Test
    @DisplayName("an unknown field in the body is refused, not ignored")
    void unknownFieldIsRefused() throws Exception {
        Snapshot before = snapshot();

        // Mass assignment: a client trying to dictate the recorded status, or
        // to name a different initiator, must be refused outright.
        mockMvc.perform(postTransfer(loginAs(ALICE_EMAIL), """
                        {"sourceAccountId":%d,"destinationAccountId":%d,"amount":10.00,
                         "status":"REJECTED","initiatedBy":%d}"""
                        .formatted(aliceCurrentId, aliceSavingsId, bobId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertUnchanged(before);
    }

    @Test
    @DisplayName("an unauthenticated guess at another customer's account changes nothing")
    void unauthenticatedAttemptChangesNothing() throws Exception {
        Snapshot before = snapshot();

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(bobCurrentId, aliceSavingsId, "500.00", null)))
                .andExpect(status().isUnauthorized());

        assertUnchanged(before);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    /** State that a refused transfer must leave exactly as it found it. */
    private record Snapshot(BigDecimal aliceCurrent, BigDecimal aliceSavings,
                            BigDecimal aliceUsd, BigDecimal bobCurrent,
                            int transfers, int movements) {
    }

    private Snapshot snapshot() {
        return new Snapshot(
                balanceOf(aliceCurrentId), balanceOf(aliceSavingsId),
                balanceOf(aliceUsdId), balanceOf(bobCurrentId),
                countTransfers(), countAllMovements());
    }

    /**
     * Asserts that nothing at all was written.
     *
     * <p>Checking the error code alone would let a partial write through: the
     * response would look correct while the money had already left the account.
     * This is the assertion that makes the atomicity requirement testable.</p>
     */
    private void assertUnchanged(Snapshot before) {
        assertThat(snapshot())
                .as("a refused transfer must not modify any balance or write any row")
                .isEqualTo(before);
    }

    private ResultActions transfer(String email, long source, long destination,
                                   String amount, String description) throws Exception {
        return mockMvc.perform(postTransfer(loginAs(email),
                transferBody(source, destination, amount, description)));
    }

    /** Performs a transfer that is expected to succeed and returns its reference. */
    private String transferReference(String email, long source, long destination,
                                     String amount, String description) throws Exception {
        MvcResult result = transfer(email, source, destination, amount, description)
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).path("reference").asText();
    }

    private MockHttpServletRequestBuilder postTransfer(String token, String body) {
        return post("/api/transfers")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String transferBody(long source, long destination, String amount, String description) {
        String descriptionJson = (description == null)
                ? "null"
                : "\"" + description + "\"";
        return """
                {"sourceAccountId":%d,"destinationAccountId":%d,"amount":%s,"description":%s}"""
                .formatted(source, destination, amount, descriptionJson);
    }

    /** Asserts the request is refused as a validation failure naming {@code field}. */
    private void assertRejectedAsInvalid(String amount, String field) throws Exception {
        Snapshot before = snapshot();

        transfer(ALICE_EMAIL, aliceCurrentId, aliceSavingsId, amount, "Invalid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field));

        assertUnchanged(before);
    }

    private long transferIdOf(String reference) {
        Long id = jdbcTemplate.queryForObject(
                "select id from transfers where reference = ?", Long.class, reference);
        assertThat(id).as("transfer %s must exist", reference).isNotNull();
        return id;
    }

    private JsonNode read(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
