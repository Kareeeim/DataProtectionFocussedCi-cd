package com.demobank;

import com.demobank.support.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorisation: a customer may only ever reach their own accounts.
 *
 * <p>Authentication answers "who are you"; this class is about the second
 * question, "and are <em>you</em> allowed to see <em>this</em>?". Getting the
 * first right while getting the second wrong is the classic broken-object-level-
 * authorisation flaw: the attacker holds a perfectly valid account of their own
 * and simply changes the id in the URL.</p>
 *
 * <p>The rule these tests pin down is that a foreign account is reported
 * <strong>exactly</strong> like one that does not exist. Returning 403 for
 * "exists but not yours" would confirm the existence of the row, which is
 * already a disclosure - and it turns the id space into something worth
 * enumerating.</p>
 */
@DisplayName("Authorisation")
class AccountAccessApiTest extends BaseIntegrationTest {

    @Test
    @DisplayName("a customer sees only their own accounts")
    void listingReturnsOnlyOwnedAccounts() throws Exception {
        JsonNode aliceAccounts = getJson("/api/accounts", loginAs(ALICE_EMAIL));

        assertThat(accountNumbers(aliceAccounts))
                .containsExactlyInAnyOrder(ALICE_CURRENT_IBAN, ALICE_SAVINGS_IBAN,
                        ALICE_USD_IBAN, ALICE_BILLS_IBAN)
                .doesNotContain(BOB_CURRENT_IBAN);

        JsonNode bobAccounts = getJson("/api/accounts", loginAs(BOB_EMAIL));

        assertThat(accountNumbers(bobAccounts))
                .containsExactly(BOB_CURRENT_IBAN)
                .doesNotContain(ALICE_CURRENT_IBAN);
    }

    @Test
    @DisplayName("an account response exposes the owner's name but no contact details")
    void accountResponseExposesOnlyWhatTheUiNeeds() throws Exception {
        JsonNode accounts = getJson("/api/accounts", loginAs(ALICE_EMAIL));
        JsonNode account = accounts.get(0);

        List<String> fields = new ArrayList<>();
        account.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrder(
                "id", "accountNumber", "ownerName", "currency", "balance");
        assertThat(account.path("ownerName").asText()).isEqualTo("Alice Rossi");
        // The owner's email is not part of the payload: the UI has no use for
        // it, and a response is a bad place to accumulate identifiers.
        assertThat(account.toString()).doesNotContain(ALICE_EMAIL);
    }

    @Test
    @DisplayName("money is sent as a decimal string, never as a JSON number")
    void moneyIsSerialisedAsAString() throws Exception {
        JsonNode accounts = getJson("/api/accounts", loginAs(ALICE_EMAIL));
        JsonNode current = findByNumber(accounts, ALICE_CURRENT_IBAN);

        // A JSON number would be parsed into an IEEE-754 double by the browser,
        // which cannot represent every decimal exactly. Keeping it a string
        // preserves the value and forces the client to handle money as a
        // decimal.
        assertThat(current.get("balance").isTextual())
                .as("balance must be a JSON string")
                .isTrue();
        assertThat(current.get("balance").asText()).isEqualTo("1000.00");
    }

    @Test
    @DisplayName("another customer's account is reported as not found")
    void foreignAccountIsNotFound() throws Exception {
        mockMvc.perform(get("/api/accounts/{id}", bobCurrentId)
                        .header("Authorization", bearer(loginAs(ALICE_EMAIL))))
                .andExpect(status().isNotFound())
                // RESOURCE_NOT_FOUND, not NOT_FOUND: the route exists and was
                // reached, the row behind it is what is absent - or belongs to
                // somebody else, which the API refuses to distinguish.
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a foreign account and a nonexistent one are indistinguishable")
    void foreignAndMissingAccountsProduceTheSameResponse() throws Exception {
        String token = loginAs(ALICE_EMAIL);

        MvcResult foreign = mockMvc.perform(get("/api/accounts/{id}", bobCurrentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andReturn();

        MvcResult missing = mockMvc.perform(get("/api/accounts/{id}", 999_999L)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(observable(foreign)).isEqualTo(observable(missing));
    }

    @Test
    @DisplayName("guessing account ids reveals nothing")
    void idsCannotBeEnumerated() throws Exception {
        String token = loginAs(ALICE_EMAIL);

        // Walk the whole neighbourhood of the real ids and confirm every one
        // that is not Alice's answers identically.
        for (long candidate = aliceCurrentId - 2; candidate <= bobCurrentId + 2; candidate++) {
            if (candidate == aliceCurrentId || candidate == aliceSavingsId
                    || candidate == aliceUsdId || candidate == aliceBillsId) {
                continue;
            }
            mockMvc.perform(get("/api/accounts/{id}", candidate)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("another customer's statement is refused even when it has movements")
    void foreignStatementIsRefused() throws Exception {
        // Bob's account genuinely has history. The refusal must come from the
        // ownership check, not from the statement being empty.
        insertMovement(bobCurrentId, Instant.now().minusSeconds(3600),
                "Bob private purchase", "DEBIT", "10.00", "490.00");

        MvcResult result = mockMvc.perform(get("/api/accounts/{id}/movements", bobCurrentId)
                        .header("Authorization", bearer(loginAs(ALICE_EMAIL))))
                .andExpect(status().isNotFound())
                .andReturn();

        // Not one byte of Bob's data may appear in the refusal.
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Bob private purchase");
    }

    @Test
    @DisplayName("a customer cannot read a statement through their own account id")
    void statementRequiresOwnershipNotJustAuthentication() throws Exception {
        // Alice reading her own statement works...
        insertMovement(aliceCurrentId, Instant.now().minusSeconds(3600),
                "Alice purchase", "DEBIT", "10.00", "990.00");

        mockMvc.perform(get("/api/accounts/{id}/movements", aliceCurrentId)
                        .header("Authorization", bearer(loginAs(ALICE_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description").value("Alice purchase"));

        // ...and the same call with Bob's id, holding Alice's token, does not.
        mockMvc.perform(get("/api/accounts/{id}/movements", bobCurrentId)
                        .header("Authorization", bearer(loginAs(ALICE_EMAIL))))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private JsonNode getJson(String path, String token) throws Exception {
        String body = mockMvc.perform(get(path).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private List<String> accountNumbers(JsonNode accounts) {
        List<String> numbers = new ArrayList<>();
        accounts.forEach(account -> numbers.add(account.path("accountNumber").asText()));
        return numbers;
    }

    private JsonNode findByNumber(JsonNode accounts, String accountNumber) {
        for (JsonNode account : accounts) {
            if (accountNumber.equals(account.path("accountNumber").asText())) {
                return account;
            }
        }
        throw new AssertionError("Account " + accountNumber + " not present in the response");
    }

    private String observable(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return result.getResponse().getStatus()
                + "|" + body.path("code").asText()
                + "|" + body.path("message").asText();
    }
}
