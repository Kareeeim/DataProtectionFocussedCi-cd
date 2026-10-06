package com.demobank.repository;

import com.demobank.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Data access for {@link User}.
 *
 * <p>All queries are derived (no string concatenation anywhere), so every
 * parameter travels to MySQL as a bound parameter of a prepared statement.
 * That is what makes SQL injection structurally impossible in this layer:
 * user input is never interpreted as SQL.</p>
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Looks a user up by the login email.
     *
     * <p>The caller must pass an already normalised (trimmed, lowercased)
     * address; {@code AuthService} does that. The underlying column uses a
     * case-insensitive collation with a unique index, so a mixed-case variant
     * of an existing address cannot be registered twice.</p>
     */
    Optional<User> findByEmail(String email);
}
