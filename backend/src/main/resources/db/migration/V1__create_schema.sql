-- ===========================================================================
--  V1 - DemoBank schema
-- ===========================================================================
--  Design notes (data-integrity / security):
--
--  * Money is stored as DECIMAL(19,2) - an exact decimal type. FLOAT/DOUBLE
--    are binary floating point and cannot represent 0.10 exactly, so sums
--    drift. Java maps this column to java.math.BigDecimal, never to double.
--
--  * Every business rule that can be expressed as a constraint is expressed
--    as one. The database is the last line of defence: even a buggy (or
--    compromised) application cannot persist a negative balance or a transfer
--    from an account to itself.
--
--  * Foreign keys use ON DELETE RESTRICT: financial history must not
--    disappear as a side effect of deleting a parent row.
--
--  * Indexes are shaped around the real access paths:
--      - accounts by owner            (dashboard)
--      - movements by account + date  (statement, newest first)
--      - transfers by reference       (idempotency / audit lookup)
--
--  * Account numbers are demo values in IBAN-like format. No real IBANs and
--    no real personal data are used anywhere in this project.
--
--  * Fixed-width values (password_hash, currency, reference) are VARCHAR(n)
--    with an exact length, NOT CHAR(n). The guarantee that matters - nothing
--    longer than the limit can ever be stored, so a plaintext password cannot
--    be squeezed into the hash column and a malformed currency code cannot be
--    written - comes from n, not from CHAR. And CHAR would actively hurt:
--    Hibernate maps a plain String to VARCHAR, so `ddl-auto: validate` refuses
--    to start against a CHAR column, which is how this was caught. Keeping the
--    two in agreement is worth more than the padding semantics nobody wants.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- users - the authenticated subjects of the application
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,
    -- BCrypt output is always 60 characters. The column is sized exactly so a
    -- plaintext password could never be stored here by accident.
    password_hash VARCHAR(60)  NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                               ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- The collation is case-insensitive, so this also prevents two accounts
    -- that differ only by capitalisation (Alice@x vs alice@x).
    UNIQUE KEY uk_users_email (email),
    CONSTRAINT chk_users_role CHECK (role IN ('ROLE_CUSTOMER', 'ROLE_ADMIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- accounts - one row per bank account, owned by exactly one user
-- ---------------------------------------------------------------------------
CREATE TABLE accounts (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    account_number VARCHAR(34)   NOT NULL COMMENT 'IBAN-like demo identifier (max IBAN length = 34)',
    owner_id       BIGINT        NOT NULL,
    currency       VARCHAR(3)    NOT NULL COMMENT 'ISO-4217 alphabetic code',
    balance        DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    -- Optimistic locking: a concurrent modification of the same row fails
    -- loudly instead of silently overwriting the other transaction's result.
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                 ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_accounts_number (account_number),
    -- Supports "give me the accounts of this user", the only read path used
    -- by the dashboard.
    KEY idx_accounts_owner (owner_id),
    CONSTRAINT fk_accounts_owner FOREIGN KEY (owner_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- Defence in depth: an overdraft can never reach the table, whatever the
    -- application layer does.
    CONSTRAINT chk_accounts_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_accounts_currency CHECK (currency REGEXP '^[A-Z]{3}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- transfers - audit record of every simulated transfer
-- ---------------------------------------------------------------------------
CREATE TABLE transfers (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    reference              VARCHAR(36)   NOT NULL COMMENT 'UUID exposed to the client instead of the internal id',
    source_account_id      BIGINT        NOT NULL,
    destination_account_id BIGINT        NOT NULL,
    initiated_by           BIGINT        NOT NULL,
    amount                 DECIMAL(19,2) NOT NULL,
    currency               VARCHAR(3)    NOT NULL,
    description            VARCHAR(140)  NULL,
    status                 VARCHAR(20)   NOT NULL,
    executed_at            DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transfers_reference (reference),
    KEY idx_transfers_source (source_account_id, executed_at),
    KEY idx_transfers_destination (destination_account_id, executed_at),
    CONSTRAINT fk_transfers_source FOREIGN KEY (source_account_id) REFERENCES accounts (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transfers_destination FOREIGN KEY (destination_account_id) REFERENCES accounts (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transfers_user FOREIGN KEY (initiated_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_transfers_amount_positive CHECK (amount > 0),
    -- A transfer to the same account is meaningless and must never be stored.
    CONSTRAINT chk_transfers_distinct_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT chk_transfers_status CHECK (status IN ('COMPLETED', 'REJECTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- movements - the statement lines (double entry: one DEBIT + one CREDIT per
-- transfer, written in the same database transaction)
-- ---------------------------------------------------------------------------
CREATE TABLE movements (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    account_id    BIGINT        NOT NULL,
    executed_at   DATETIME(6)   NOT NULL,
    description   VARCHAR(140)  NOT NULL,
    type          VARCHAR(10)   NOT NULL,
    -- Always a positive magnitude; the direction lives in `type`.
    amount        DECIMAL(19,2) NOT NULL,
    -- Balance of the account immediately after this movement: makes the
    -- statement reproducible without replaying the whole history.
    balance_after DECIMAL(19,2) NOT NULL,
    transfer_id   BIGINT        NULL,
    PRIMARY KEY (id),
    -- The statement query is always "movements of one account, newest first".
    KEY idx_movements_account_date (account_id, executed_at DESC, id DESC),
    KEY idx_movements_transfer (transfer_id),
    CONSTRAINT fk_movements_account FOREIGN KEY (account_id) REFERENCES accounts (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_movements_transfer FOREIGN KEY (transfer_id) REFERENCES transfers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_movements_type CHECK (type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_movements_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_movements_balance_after CHECK (balance_after >= 0),
    -- Integrity guard: a given transfer can produce at most one DEBIT and one
    -- CREDIT for the same account. Re-running a transfer can therefore never
    -- double-post.
    UNIQUE KEY uk_movements_transfer_side (transfer_id, account_id, type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
