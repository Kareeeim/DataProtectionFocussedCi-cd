package com.demobank.service;

import com.demobank.domain.Account;
import com.demobank.domain.Movement;
import com.demobank.domain.MovementType;
import com.demobank.domain.Transfer;
import com.demobank.domain.User;
import com.demobank.dto.TransferRequest;
import com.demobank.dto.TransferResponse;
import com.demobank.exception.InsufficientFundsException;
import com.demobank.exception.InvalidOperationException;
import com.demobank.exception.ResourceNotFoundException;
import com.demobank.repository.AccountRepository;
import com.demobank.repository.MovementRepository;
import com.demobank.repository.TransferRepository;
import com.demobank.repository.UserRepository;
import com.demobank.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes the simulated transfers between two accounts of the same customer.
 *
 * <h2>Atomicity</h2>
 * <p>A transfer touches four rows: the source balance, the destination balance,
 * the audit record and two statement lines. The specification requires that
 * either all of it is stored or none of it is, so the whole method runs inside
 * one database transaction and every failure path throws - which rolls the
 * transaction back. There is no partial-success branch and no
 * "save what we can" fallback.</p>
 *
 * <h2>Concurrency</h2>
 * <p>Two requests spending from the same account at the same moment must not
 * both see the old balance and both succeed. The method therefore takes a
 * <strong>pessimistic write lock</strong> ({@code SELECT ... FOR UPDATE}) on
 * both accounts before reading their balances. The second transaction blocks
 * until the first commits, then reads the updated balance and is correctly
 * refused if the money is already gone. The rows are locked in ascending id
 * order, so two transfers running in opposite directions cannot deadlock.</p>
 *
 * <p>Under {@code READ_COMMITTED} the locked read always sees the latest
 * committed value, which is exactly the guarantee needed here. The stricter
 * {@code REPEATABLE_READ} default of MySQL would also work but takes more gap
 * locks and deadlocks more readily, for no benefit in this access pattern.</p>
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    /** Matches the {@code VARCHAR(140)} width of the description columns. */
    private static final int DESCRIPTION_MAX_LENGTH = 140;

    private final AccountRepository accountRepository;
    private final MovementRepository movementRepository;
    private final TransferRepository transferRepository;
    private final UserRepository userRepository;

    public TransferService(AccountRepository accountRepository,
                           MovementRepository movementRepository,
                           TransferRepository transferRepository,
                           UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.movementRepository = movementRepository;
        this.transferRepository = transferRepository;
        this.userRepository = userRepository;
    }

    /**
     * Performs the transfer.
     *
     * <p>The order of the checks is deliberate: the cheapest and least
     * privileged ones come first, so a malformed or unauthorised request is
     * rejected before any row is locked and before any work is done.</p>
     *
     * @param request validated payload
     * @param caller  identity taken from the access token - the only source of
     *                truth about who is asking
     * @throws InvalidOperationException  same account, or amount that is not a
     *                                    positive value with two decimals
     * @throws ResourceNotFoundException  an account does not exist or is not
     *                                    the caller's
     * @throws InsufficientFundsException the source balance cannot cover it
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public TransferResponse execute(TransferRequest request, AuthenticatedUser caller) {

        Long sourceId = request.sourceAccountId();
        Long destinationId = request.destinationAccountId();

        // 1. The two accounts must be distinct. The database enforces this too
        //    (chk_transfers_distinct_accounts); checking here turns a would-be
        //    500 into a clear 400.
        if (sourceId.equals(destinationId)) {
            throw new InvalidOperationException("SAME_ACCOUNT",
                    "Source and destination accounts must be different.");
        }

        BigDecimal amount = normaliseAmount(request.amount());

        // 2. Ownership, checked with a cheap indexed probe that does not load
        //    the entity. Doing it before taking any lock means an attempt on a
        //    foreign account never locks a row belonging to another customer.
        if (!accountRepository.existsByIdAndOwnerId(sourceId, caller.id())) {
            throw new ResourceNotFoundException("Source account not found.");
        }
        if (!accountRepository.existsByIdAndOwnerId(destinationId, caller.id())) {
            throw new ResourceNotFoundException("Destination account not found.");
        }

        // 3. Lock both rows and read their authoritative balances. The lock is
        //    what makes the balance check below meaningful: without it, two
        //    concurrent transfers could both read the same balance and both
        //    pass the check.
        Map<Long, Account> locked = accountRepository
                .findAllByIdForUpdate(List.of(sourceId, destinationId))
                .stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        Account source = locked.get(sourceId);
        Account destination = locked.get(destinationId);
        if (source == null || destination == null) {
            // Reachable only if a row disappeared between the probe and the
            // lock. Treated as "not found" and rolled back.
            throw new ResourceNotFoundException("Account not found.");
        }

        // 4. Cross-currency transfers are out of scope: converting would need a
        //    rate and a rounding policy, and silently treating 100 USD as 100
        //    EUR would be wrong. Refused explicitly rather than approximated.
        if (!source.getCurrency().equals(destination.getCurrency())) {
            throw new InvalidOperationException("CURRENCY_MISMATCH",
                    "Transfers between accounts with different currencies are not supported.");
        }

        // 5. Sufficient funds, evaluated against the locked balance. This is an
        //    explicit check so the client gets a precise reason; Account#debit
        //    repeats it as a safety net, and the CHECK constraint on the column
        //    is the final backstop.
        if (source.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient balance on the source account for this transfer.");
        }

        // 6. Apply both sides. The accounts are managed entities: Hibernate
        //    flushes the two UPDATE statements at commit, inside the same
        //    transaction as everything else.
        source.debit(amount);
        destination.credit(amount);

        Instant executedAt = Instant.now();

        // 7. One audit record for the operation as a whole. `getReferenceById`
        //    returns a proxy and avoids a SELECT for a row we only need as a
        //    foreign key.
        User initiator = userRepository.getReferenceById(caller.id());
        Transfer transfer = new Transfer(source, destination, initiator, amount,
                trimmedDescription(request.description()), executedAt);
        transferRepository.save(transfer);

        // 8. The two statement legs, sharing the transfer's timestamp. Written
        //    after the transfer so that the foreign key is available.
        movementRepository.save(new Movement(source, MovementType.DEBIT, amount,
                source.getBalance(), outgoingLabel(source, destination, request.description()),
                transfer, executedAt));
        movementRepository.save(new Movement(destination, MovementType.CREDIT, amount,
                destination.getBalance(), incomingLabel(source, destination, request.description()),
                transfer, executedAt));

        log.info("Transfer {} completed: {} {} from account {} to account {} by user {}",
                transfer.getReference(), amount, transfer.getCurrency(),
                source.getAccountNumber(), destination.getAccountNumber(), caller.id());

        // Mapping happens inside the transaction, which is required: the
        // response reads each account's owner and `open-in-view` is disabled.
        return TransferResponse.from(transfer);
    }

    /**
     * Forces the amount to exactly two decimal places, without rounding.
     *
     * <p>{@code RoundingMode.UNNECESSARY} makes {@code setScale} throw instead
     * of rounding, so a value such as {@code 10.005} is rejected rather than
     * silently turned into {@code 10.01}. Silently inventing or destroying a
     * fraction of a cent is exactly the kind of behaviour that makes financial
     * software untrustworthy. The validator already rejects more than two
     * decimals; this is the second, arithmetic guarantee that what reaches the
     * {@code DECIMAL(19,2)} column has the expected scale.</p>
     */
    private BigDecimal normaliseAmount(BigDecimal raw) {
        BigDecimal amount;
        try {
            amount = raw.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new InvalidOperationException("INVALID_AMOUNT",
                    "Amount must have at most 2 decimal places.");
        }
        if (amount.signum() <= 0) {
            throw new InvalidOperationException("INVALID_AMOUNT",
                    "Amount must be a positive value.");
        }
        return amount;
    }

    private String outgoingLabel(Account source, Account destination, String userDescription) {
        return truncate("Transfer to " + destination.getAccountNumber()
                + suffix(userDescription));
    }

    private String incomingLabel(Account source, Account destination, String userDescription) {
        return truncate("Transfer from " + source.getAccountNumber()
                + suffix(userDescription));
    }

    private String suffix(String userDescription) {
        String clean = trimmedDescription(userDescription);
        return clean == null ? "" : " - " + clean;
    }

    /** Returns the trimmed description, or {@code null} when nothing was given. */
    private String trimmedDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return truncate(description.trim());
    }

    /**
     * Cuts a value down to the width of the column that will hold it, without
     * splitting a character in half.
     *
     * <p>The statement label is composed here, not supplied by the caller, so it
     * can be longer than the raw description: "Transfer to " plus an account
     * number plus the description. {@code substring} counts UTF-16 code units,
     * so a plain cut at the limit can land between the two halves of a surrogate
     * pair - an emoji in the description is the obvious way in - and leave a
     * lone surrogate behind. MySQL rejects that as invalid utf8mb4 and the
     * insert fails, which would turn a perfectly valid transfer into a 500.
     * Stepping back one unit when the cut would split a pair costs nothing and
     * removes the failure entirely.</p>
     */
    private String truncate(String value) {
        if (value.length() <= DESCRIPTION_MAX_LENGTH) {
            return value;
        }
        int end = DESCRIPTION_MAX_LENGTH;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
