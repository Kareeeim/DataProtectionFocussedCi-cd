package com.demobank.repository;

import com.demobank.domain.Movement;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Data access for {@link Movement}.
 *
 * <p>The filter query below is written in JPQL with named parameters, which
 * Hibernate compiles into a JDBC prepared statement. The date bounds are
 * therefore bound values, never concatenated text - the reason a payload such
 * as {@code from=2020-01-01' OR '1'='1} is simply rejected by the date parser
 * instead of altering the query.</p>
 */
@Repository
public interface MovementRepository extends JpaRepository<Movement, Long> {

    /**
     * Statement of one account, newest first, with optional date filtering.
     *
     * <p>The query always receives concrete bounds: {@code MovementService}
     * substitutes {@link Instant#EPOCH} for a missing "from" and a far-future
     * instant for a missing "to". Avoiding {@code :param IS NULL} checks keeps
     * the statement portable across MySQL and the H2 compatibility mode used by
     * the tests, and lets the optimiser use the
     * {@code (account_id, executed_at DESC)} index without guessing.</p>
     *
     * <p>The ordering is {@code executed_at DESC, id DESC}: the id is the
     * tie-breaker, so two movements written in the same microsecond (which
     * happens on every transfer, since both legs share a timestamp) still come
     * back in a deterministic order.</p>
     *
     * @param accountId the account whose statement is requested - the caller
     *                  must have already verified that it belongs to the
     *                  authenticated user
     * @param from      inclusive lower bound
     * @param to        exclusive upper bound
     * @param pageable limits the number of rows returned
     */
    @Query("""
            select m from Movement m
             where m.account.id = :accountId
               and m.executedAt >= :from
               and m.executedAt <  :to
             order by m.executedAt desc, m.id desc
            """)
    List<Movement> findStatement(@Param("accountId") Long accountId,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to,
                                 Pageable pageable);

    /** Number of statement lines matching the same filter. */
    @Query("""
            select count(m) from Movement m
             where m.account.id = :accountId
               and m.executedAt >= :from
               and m.executedAt <  :to
            """)
    long countStatement(@Param("accountId") Long accountId,
                        @Param("from") Instant from,
                        @Param("to") Instant to);
}
