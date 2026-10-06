package com.demobank.service;

import com.demobank.dto.MovementResponse;
import com.demobank.exception.InvalidOperationException;
import com.demobank.exception.ResourceNotFoundException;
import com.demobank.repository.AccountRepository;
import com.demobank.repository.MovementRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account statements: the movements of one account, filtered and ordered.
 */
@Service
@Transactional(readOnly = true)
public class MovementService {

    /** Rows returned when the client does not ask for a specific size. */
    private static final int DEFAULT_LIMIT = 200;

    /**
     * Hard ceiling on rows per request. Without a cap, a single call could ask
     * for the entire history of an account and turn a read endpoint into a
     * memory and bandwidth amplifier.
     */
    private static final int MAX_LIMIT = 500;

    private final MovementRepository movementRepository;
    private final AccountRepository accountRepository;

    public MovementService(MovementRepository movementRepository,
                           AccountRepository accountRepository) {
        this.movementRepository = movementRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Movements of one account, newest first, optionally restricted to a date
     * range.
     *
     * <p>The ownership check happens <em>before</em> the statement query and is
     * not optional: without it, {@code /api/accounts/1/movements} would happily
     * return the transactions of any customer whose id the caller guesses. The
     * check is an {@code existsByIdAndOwnerId} probe - a cheap indexed lookup
     * that does not even load the account.</p>
     *
     * @param accountId account whose statement is requested
     * @param ownerId   authenticated user; must own the account
     * @param from      inclusive lower bound, or {@code null} for "since the
     *                  beginning"
     * @param to        inclusive upper bound, or {@code null} for "up to now"
     * @param limit     maximum rows; {@code null} uses the default
     * @throws ResourceNotFoundException if the account is not the caller's
     * @throws InvalidOperationException if {@code from} is after {@code to}
     */
    public List<MovementResponse> listMovements(Long accountId, Long ownerId,
                                                LocalDate from, LocalDate to, Integer limit) {

        if (!accountRepository.existsByIdAndOwnerId(accountId, ownerId)) {
            // Same response whether the account is missing or simply not the
            // caller's: the API never confirms the existence of a foreign row.
            throw new ResourceNotFoundException("Account not found.");
        }

        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidOperationException("INVALID_DATE_RANGE",
                    "The 'from' date must not be after the 'to' date.");
        }

        // The query compares against a half-open interval [from, to). Since the
        // user picks calendar days and expects both ends to be included, the
        // upper bound is shifted by one day. Everything is computed in UTC so
        // that the result does not depend on the server's timezone.
        Instant fromInstant = (from != null)
                ? from.atStartOfDay(ZoneOffset.UTC).toInstant()
                : Instant.EPOCH;
        Instant toInstant = (to != null)
                ? to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                : Instant.now().plusSeconds(86_400);

        int effectiveLimit = (limit == null || limit <= 0)
                ? DEFAULT_LIMIT
                : Math.min(limit, MAX_LIMIT);

        return movementRepository
                .findStatement(accountId, fromInstant, toInstant, PageRequest.of(0, effectiveLimit))
                .stream()
                .map(MovementResponse::from)
                .toList();
    }
}
