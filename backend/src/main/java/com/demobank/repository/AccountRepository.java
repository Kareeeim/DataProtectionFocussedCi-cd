package com.demobank.repository;

import com.demobank.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Data access for {@link Account}.
 *
 * <p><strong>Every read method is scoped by owner.</strong> There is deliberately
 * no {@code findById} usage in the application: a lookup by primary key alone
 * is the classic Insecure Direct Object Reference (IDOR) - an attacker simply
 * increments the id in {@code /api/accounts/2/movements} and reads somebody
 * else's statement. Scoping the query by {@code ownerId}, which comes from the
 * verified JWT and never from the request body, makes that attack return "not
 * found" instead.</p>
 *
 * <p>{@code @EntityGraph} forces the owner to be fetched in the same query.
 * Because {@code spring.jpa.open-in-view} is disabled, a lazy owner would
 * otherwise blow up with a {@code LazyInitializationException} once the mapping
 * code touches it outside the transaction.</p>
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * All accounts belonging to one customer, in a stable order.
     *
     * @param ownerId identifier taken from the authenticated principal
     */
    @EntityGraph(attributePaths = "owner")
    List<Account> findAllByOwnerIdOrderByAccountNumberAsc(Long ownerId);

    /**
     * Loads one account <em>only if</em> it belongs to the given customer.
     *
     * <p>Returns an empty {@link Optional} both when the account does not exist
     * and when it belongs to somebody else. The two cases are indistinguishable
     * to the caller on purpose: a distinct "403 forbidden" answer would confirm
     * that the id exists, letting an attacker enumerate valid account ids.</p>
     */
    @EntityGraph(attributePaths = "owner")
    Optional<Account> findByIdAndOwnerId(Long id, Long ownerId);

    /**
     * Locks both sides of a transfer for the duration of the transaction.
     *
     * <p>{@code PESSIMISTIC_WRITE} translates to {@code SELECT ... FOR UPDATE}.
     * It is what makes the transfer safe under concurrency: two simultaneous
     * transfers from the same account are serialised by the database, so the
     * second one reads the balance <em>after</em> the first one committed and
     * cannot spend the same money twice (the "lost update" problem).</p>
     *
     * <p>Rows are read through the primary key, so InnoDB locks them in a
     * deterministic ascending id order. Two transfers running in opposite
     * directions therefore acquire their locks in the same order and cannot
     * deadlock against each other.</p>
     *
     * @param ids exactly the two account ids involved in the transfer
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id asc")
    List<Account> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    /** True when the account exists and belongs to the given customer. */
    boolean existsByIdAndOwnerId(Long id, Long ownerId);
}
