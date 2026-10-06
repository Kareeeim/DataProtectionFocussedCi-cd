-- ===========================================================================
--  V2 - Demo data
-- ===========================================================================
--  Everything in this file is FICTIONAL. The names, the email addresses, the
--  account numbers and the movements are invented for teaching purposes.
--  No real person, no real bank, no real account and no real credential is
--  represented here. Never load this dataset into a real environment.
--
--  THE DEMO PASSWORD IS:  Demo1234!
--
--  What is stored below is NOT that password: it is its BCrypt hash, cost
--  factor 12 (~250 ms per verification on commodity hardware), with a unique
--  random salt per user. That is exactly what the application stores when a
--  user is created, and it is why the plaintext is unrecoverable from the
--  database dump. The two hashes differ although the password is the same,
--  which is the visible proof that salting is in effect.
--
--  To verify by hand that a hash matches the demo password:
--      htpasswd -bnBC 12 "" 'Demo1234!'      # compare the shape, not the salt
--  or simply log in through the UI with alice@demo.local / Demo1234!
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Users (2 demo customers)
-- ---------------------------------------------------------------------------
INSERT INTO users (email, password_hash, full_name, role, enabled) VALUES
    ('alice@demo.local',
     '$2b$12$XqZGIu0CZrGDXqDzh3K7D.EYuLTE4PutjPPSi9P12uGvgg322cnB2',
     'Alice Rossi', 'ROLE_CUSTOMER', 1),
    ('bob@demo.local',
     '$2b$12$dRYyTZd5kGBLItyeeNx3aeiPF7.iaLvi8KbWmFkyOhAJAu6ZFD0cO',
     'Bob Bianchi', 'ROLE_CUSTOMER', 1);

-- ---------------------------------------------------------------------------
-- Accounts
--   Alice owns two accounts (so she can transfer between them - the realistic
--   demo case), Bob owns one. Alice can never see Bob's account, and the test
--   suite proves it.
-- ---------------------------------------------------------------------------
INSERT INTO accounts (account_number, owner_id, currency, balance) VALUES
    ('IT60X0542811101000000123456', (SELECT id FROM users WHERE email = 'alice@demo.local'), 'EUR', 2540.75),
    ('IT12A0300203280000400161234', (SELECT id FROM users WHERE email = 'alice@demo.local'), 'EUR',  180.00),
    ('IT99Z0100003245348008123456', (SELECT id FROM users WHERE email = 'bob@demo.local'),   'EUR', 1000.00);

-- ---------------------------------------------------------------------------
-- Movements
--   Each account's history sums up exactly to its current balance, and every
--   `balance_after` is consistent with the running total. That consistency is
--   deliberate: it is what makes the statement believable, and it is what the
--   integration tests assert against.
--
--   Dates are relative to the moment the migration runs, so the date-range
--   filters in the UI always have fresh data to work with.
-- ---------------------------------------------------------------------------

-- Alice / main account: 2000.00 - 200.00 + 1000.00 - 259.25 = 2540.75
INSERT INTO movements (account_id, executed_at, description, type, amount, balance_after) VALUES
    ((SELECT id FROM accounts WHERE account_number = 'IT60X0542811101000000123456'),
     DATE_SUB(NOW(6), INTERVAL 90 DAY), 'Initial deposit',            'CREDIT', 2000.00, 2000.00),
    ((SELECT id FROM accounts WHERE account_number = 'IT60X0542811101000000123456'),
     DATE_SUB(NOW(6), INTERVAL 45 DAY), 'Utility bill - electricity', 'DEBIT',   200.00, 1800.00),
    ((SELECT id FROM accounts WHERE account_number = 'IT60X0542811101000000123456'),
     DATE_SUB(NOW(6), INTERVAL 20 DAY), 'Salary - September',         'CREDIT', 1000.00, 2800.00),
    ((SELECT id FROM accounts WHERE account_number = 'IT60X0542811101000000123456'),
     DATE_SUB(NOW(6), INTERVAL  3 DAY), 'Supermarket',                'DEBIT',   259.25, 2540.75);

-- Alice / savings account: 0.00 + 180.00 = 180.00
INSERT INTO movements (account_id, executed_at, description, type, amount, balance_after) VALUES
    ((SELECT id FROM accounts WHERE account_number = 'IT12A0300203280000400161234'),
     DATE_SUB(NOW(6), INTERVAL 10 DAY), 'Transfer in from main account', 'CREDIT', 180.00, 180.00);

-- Bob / main account: 500.00 + 500.00 = 1000.00
INSERT INTO movements (account_id, executed_at, description, type, amount, balance_after) VALUES
    ((SELECT id FROM accounts WHERE account_number = 'IT99Z0100003245348008123456'),
     DATE_SUB(NOW(6), INTERVAL 60 DAY), 'Initial deposit', 'CREDIT', 500.00, 500.00),
    ((SELECT id FROM accounts WHERE account_number = 'IT99Z0100003245348008123456'),
     DATE_SUB(NOW(6), INTERVAL 15 DAY), 'Freelance invoice', 'CREDIT', 500.00, 1000.00);
