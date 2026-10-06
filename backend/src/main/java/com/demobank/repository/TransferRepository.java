package com.demobank.repository;

import com.demobank.domain.Transfer;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Data access for {@link Transfer}.
 *
 * <p>Kept intentionally small: the transfer history a customer sees is the
 * statement of their accounts (the {@code movements} table), while this table
 * exists for auditing and for future idempotency support.</p>
 */
@Repository
public interface TransferRepository extends JpaRepository<Transfer, Long> {

    /**
     * Finds a transfer by its public UUID reference.
     *
     * <p>Used to answer "what was this operation?" without exposing the
     * sequential primary key to clients.</p>
     */
    Optional<Transfer> findByReference(String reference);
}
