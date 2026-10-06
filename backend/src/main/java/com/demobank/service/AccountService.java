package com.demobank.service;

import com.demobank.domain.Account;
import com.demobank.dto.AccountResponse;
import com.demobank.exception.ResourceNotFoundException;
import com.demobank.repository.AccountRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read operations on accounts.
 *
 * <p>Every method takes the caller's user id as a parameter and filters on it.
 * The id is supplied by the controller from the verified token - it is never
 * read from the request. That single convention is what makes horizontal
 * privilege escalation (customer A reading customer B's account) impossible
 * rather than merely unlikely.</p>
 *
 * <p>The class is annotated {@code readOnly = true}: Hibernate then skips dirty
 * checking, and the JDBC connection is marked read-only, so an accidental write
 * in this layer fails instead of succeeding quietly.</p>
 */
@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Accounts owned by the authenticated customer, ordered by account number.
     *
     * @param ownerId user id taken from the access token
     */
    public List<AccountResponse> listOwnedAccounts(Long ownerId) {
        return accountRepository.findAllByOwnerIdOrderByAccountNumberAsc(ownerId)
                .stream()
                .map(AccountResponse::from)
                .toList();
    }

    /**
     * One account, provided it belongs to the caller.
     *
     * @throws ResourceNotFoundException if the account does not exist or belongs
     *                                   to somebody else - the same answer for
     *                                   both, so the endpoint cannot be used to
     *                                   discover which ids exist
     */
    public AccountResponse getOwnedAccount(Long accountId, Long ownerId) {
        return AccountResponse.from(loadOwnedAccount(accountId, ownerId));
    }

    /**
     * Loads the entity, enforcing ownership.
     *
     * <p>Package-visible so that other services (statements) reuse exactly the
     * same ownership rule instead of re-implementing it and drifting apart.</p>
     */
    Account loadOwnedAccount(Long accountId, Long ownerId) {
        return accountRepository.findByIdAndOwnerId(accountId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found."));
    }
}
