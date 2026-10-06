package com.demobank;

import com.demobank.domain.Movement;
import com.demobank.domain.MovementType;
import com.demobank.repository.MovementRepository;
import com.demobank.support.BaseIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The all-or-nothing guarantee of a transfer.
 *
 * <p>A transfer writes five rows across three tables: two account balances, the
 * audit record and two statement lines. The specification requires that either
 * all five are stored or none is. This class attacks that requirement from two
 * directions.</p>
 *
 * <ol>
 *   <li><strong>An injected failure.</strong> The interesting case is not a
 *       validation error - those are raised before anything is written, and the
 *       refusal tests elsewhere already cover them. The dangerous case is a
 *       failure <em>after</em> the balances have been changed in the persistence
 *       context: a dropped connection, a constraint violated on the second
 *       insert, a disk that fills up between two statements. That cannot be
 *       produced from a test through the API, so the repository is wrapped in a
 *       spy that fails the second statement line, and the test then asserts that
 *       the database is byte-for-byte where it started.</li>
 *
 *   <li><strong>Concurrency.</strong> Two requests spending the same balance at
 *       the same moment must not both succeed. This is not a theoretical
 *       concern: without a lock, both transactions read the same balance, both
 *       conclude there is enough money, and both write - and the account ends up
 *       overdrawn by the amount of the second transfer.</li>
 * </ol>
 */
@DisplayName("Transfer atomicity")
class TransferAtomicityTest extends BaseIntegrationTest {

    @MockitoSpyBean
    private MovementRepository movementRepository;

    // -----------------------------------------------------------------------
    //  A failure in the middle of the transaction
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a failure after the first statement line is already written leaves no trace")
    void failureOnTheSecondStatementLineRollsEverythingBack() throws Exception {
        String token = loginAs(ALICE_EMAIL);

        // Everything that must survive the test untouched.
        BigDecimal sourceBefore = balanceOf(aliceCurrentId);
        BigDecimal destinationBefore = balanceOf(aliceSavingsId);
        Long sourceVersionBefore = accountVersion(aliceCurrentId);

        // Only the second leg is stubbed. The first one is left alone and falls
        // through to the repository's real implementation, so the outgoing
        // statement line is genuinely written before the failure - which is
        // what makes this the interesting case: a row exists and must vanish.
        //
        // The form matters. `doThrow(...).when(spy).save(...)` never invokes the
        // real method while stubbing, whereas the more familiar
        // `doAnswer(invocation -> invocation.callRealMethod())` does - and that
        // one cannot work here: @MockitoSpyBean spies a Spring Data repository
        // on its interface, and Mockito refuses to call a real method through an
        // abstract one ("Cannot call abstract real method on java object!").
        // An unstubbed call on the spy is a different path and does reach the
        // real repository.
        doThrow(new DataIntegrityViolationException("simulated failure on the second leg"))
                .when(movementRepository)
                .save(argThat(movement -> movement.getType() == MovementType.CREDIT));

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":%d,"destinationAccountId":%d,"amount":100.00}"""
                                .formatted(aliceCurrentId, aliceSavingsId)))
                // An unexpected server-side failure must stay a 5xx: it must
                // never be dressed up as a successful transfer.
                .andExpect(status().is5xxServerError());

        // Nothing was committed: no balance moved, no audit record, no
        // statement line. The optimistic-locking counter is checked too, which
        // proves that not even a no-op UPDATE reached the row.
        assertThat(balanceOf(aliceCurrentId)).isEqualByComparingTo(sourceBefore);
        assertThat(balanceOf(aliceSavingsId)).isEqualByComparingTo(destinationBefore);
        assertThat(accountVersion(aliceCurrentId)).isEqualTo(sourceVersionBefore);
        assertThat(countTransfers()).isZero();
        assertThat(countAllMovements()).isZero();
    }

    @Test
    @DisplayName("a failure on the very first statement line rolls everything back too")
    void failureOnTheFirstStatementLineRollsEverythingBack() throws Exception {
        String token = loginAs(ALICE_EMAIL);
        BigDecimal sourceBefore = balanceOf(aliceCurrentId);

        // Nothing of the statement can be written at all. The balances have
        // still been debited and credited in memory by this point, and the
        // audit record has already been inserted - all of which must vanish.
        doThrow(new DataIntegrityViolationException("simulated failure on the first leg"))
                .when(movementRepository)
                .save(any(Movement.class));

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":%d,"destinationAccountId":%d,"amount":50.00}"""
                                .formatted(aliceCurrentId, aliceSavingsId)))
                .andExpect(status().is5xxServerError());

        assertThat(balanceOf(aliceCurrentId)).isEqualByComparingTo(sourceBefore);
        assertThat(balanceOf(aliceSavingsId)).isEqualByComparingTo("250.50");
        assertThat(countTransfers()).isZero();
        assertThat(countAllMovements()).isZero();
    }

    // -----------------------------------------------------------------------
    //  Two requests, one balance
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("two simultaneous transfers cannot spend the same money twice")
    void concurrentTransfersCannotOverdrawTheAccount() throws Exception {
        String token = loginAs(ALICE_EMAIL);

        // Both requests try to move the entire 1000.00 balance, each to a
        // different account. Only one of them can possibly be satisfied.
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> toSavings = pool.submit(
                    transferTask(token, aliceSavingsId, startLine));
            Future<Integer> toBills = pool.submit(
                    transferTask(token, aliceBillsId, startLine));

            // Fire both at the same instant so that they genuinely overlap,
            // instead of one finishing before the other starts.
            startLine.countDown();

            List<Integer> statuses = List.of(
                    toSavings.get(30, TimeUnit.SECONDS),
                    toBills.get(30, TimeUnit.SECONDS));

            long succeeded = statuses.stream().filter(status -> status == 201).count();

            assertThat(succeeded)
                    .as("exactly one of two transfers spending the whole balance may succeed, "
                            + "but the statuses were " + statuses)
                    .isEqualTo(1);

            // The money was not created: one audit record, two statement lines.
            assertThat(countTransfers()).isEqualTo(1);
            assertThat(countAllMovements()).isEqualTo(2);

            // The source is empty, never negative.
            assertThat(balanceOf(aliceCurrentId)).isEqualByComparingTo("0.00");

            // And the money arrived exactly once: 250.50 was already in savings,
            // 0.00 in bills, and 1000.00 moved into one of them.
            assertThat(balanceOf(aliceSavingsId).add(balanceOf(aliceBillsId)))
                    .isEqualByComparingTo("1250.50");
        } finally {
            pool.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    /**
     * One transfer request, held at a start line so two of them can be released
     * together. Returns the HTTP status rather than asserting it, because which
     * of the two wins is decided by the database.
     */
    private Callable<Integer> transferTask(String token, long destination,
                                           CountDownLatch startLine) {
        return () -> {
            startLine.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(post("/api/transfers")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sourceAccountId":%d,"destinationAccountId":%d,"amount":1000.00}"""
                                    .formatted(aliceCurrentId, destination)))
                    .andReturn().getResponse().getStatus();
        };
    }

    private Long accountVersion(long accountId) {
        return jdbcTemplate.queryForObject(
                "select version from accounts where id = ?", Long.class, accountId);
    }
}
