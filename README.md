# DemoBank

A small, self-contained online-banking application built as a teaching exercise:
**Angular** front end, **Spring Boot** REST API, **MySQL** database, everything
running in **Docker**.

It authenticates customers, lists their accounts and statements, and executes
simulated transfers between two accounts belonging to the same customer.

> **Everything in this repository is fictional.** The customers, the account
> numbers, the movements and the credentials are invented. There is no real
> bank, no real money and no real personal data anywhere in it. It is not
> intended to be deployed on a network.

The guiding constraint of the whole project is **the security of the data the
application stores**. Every design decision below — the schema, the ownership
checks, the transaction boundaries, the container hardening, the way money is
serialised — follows from that, and [§ Security](#-security) explains each one
along with the trade-offs that were accepted deliberately.

---

## Table of contents

- [Quick start](#-quick-start)
- [Demo credentials](#-demo-credentials)
- [What the application does](#-what-the-application-does)
- [Technology stack](#-technology-stack)
- [Prerequisites](#-prerequisites)
- [Configuration](#-configuration)
- [Architecture](#-architecture)
- [Data model](#-data-model)
- [API reference](#-api-reference)
- [Security](#-security)
- [Testing](#-testing)
- [CI/CD pipeline](#-cicd-pipeline)
- [Running without Docker](#-running-without-docker)
- [Project structure](#-project-structure)
- [Troubleshooting](#-troubleshooting)
- [Known limitations](#-known-limitations)

---

## 🚀 Quick start

You need **Docker Desktop** (or Docker Engine with the Compose plugin). Nothing
else — no JDK, no Node.js, no MySQL on the host.

```bash
git clone <repository-url> demobank
cd demobank
docker compose up --build
```

The first run builds both images and pulls MySQL; it takes a few minutes. When
the logs settle, open:

| URL | What it is |
| --- | --- |
| **<http://localhost:8080>** | The application |
| <http://localhost:8080/swagger-ui.html> | Interactive API documentation (`demo` profile) |

That single port is the only thing exposed to the network. The API and the
database are reachable only from inside the Docker network — which is why the
API's own health endpoint has no public URL. To read it, ask Docker, which is
already inside:

```bash
docker compose ps                          # health of all three containers
docker compose exec backend wget -qO- http://localhost:8080/actuator/health
```

To stop everything:

```bash
docker compose down          # keeps the database volume
docker compose down -v       # also deletes the data, for a clean restart
```

If port 8080 is already in use, pick another one:

```bash
WEB_PORT=9090 docker compose up --build
```

---

## 👤 Demo credentials

The database is seeded by a Flyway migration with two fictional customers. Both
use the same demo password:

| Email | Password | Accounts |
| --- | --- | --- |
| `alice@demo.local` | `Demo1234!` | Two EUR accounts (2 540.75 and 180.00) |
| `bob@demo.local` | `Demo1234!` | One EUR account (1 000.00) |

The password is never stored: the migration contains its **BCrypt hash** (cost
factor 12, unique salt per user). Because the salt is random, the two rows hold
two different hashes of the same password — which is the visible proof that
salting is in effect.

Sign in as Alice and try to reach Bob's account by editing the URL. You will get
a `404`, exactly as if the account did not exist.

---

## 🏦 What the application does

**Authentication.** A customer signs in with an email and a password and
receives a signed JWT. Every other endpoint requires it.

**Dashboard.** All accounts belonging to the authenticated customer, with the
balance of each and a per-currency total.

**Account detail.** The statement of one account: date, description, type
(debit/credit), amount and the balance after each line. Newest first, filterable
by a date range, all filtering done server-side.

**Transfers.** Move money between two *distinct* accounts belonging to the
*same* customer. The form validates locally, asks for confirmation, and shows a
receipt with the operation reference and both new balances. The two balance
updates, the audit record and the two statement lines are written in a single
database transaction: either all of it is stored or none of it is.

**Isolation.** A customer can never see or touch another customer's data — not
through the API, not by editing an identifier in a URL.

---

## 🧱 Technology stack

| Layer | Choice | Version |
| --- | --- | --- |
| Front end | Angular (standalone components, signals) | 20.3 |
| Web server / reverse proxy | nginx (unprivileged image) | 1.27 |
| API | Spring Boot (Web, Data JPA, Security, Validation) | 3.5.16 |
| Language | Java | 21 (LTS) |
| Database | MySQL | 8.4 |
| Schema migrations | Flyway | (managed by Boot) |
| Tokens | jjwt (HMAC-SHA256) | 0.12.7 |
| API docs | springdoc-openapi | 2.8.17 |
| Test database | H2 (in-memory) | 2.3 |

Spring Boot 3.5 was chosen over 4.x deliberately: it is the current line whose
ecosystem (Spring Security 6.5, Hibernate 6, springdoc 2) is stable and
well-documented, which matters more for a teaching project than being on the
newest major version.

---

## ✅ Prerequisites

**To run the Docker stack (recommended)**

- Docker Desktop 4.x, or Docker Engine 24+ with Compose v2
- ~4 GB of free RAM (MySQL + JVM + nginx)
- Ports 8080 and 3307 free on the host

**To run the pieces locally instead**

- JDK 21
- Maven 3.9+
- Node.js 22 and npm 10+
- A MySQL 8 server (optional — the API can point at the containerised one)

---

## ⚙️ Configuration

Every credential is read from the environment. Nothing sensitive is written into
the repository, and the application **refuses to start under the `prod` profile**
if it detects one of the demo values shipped here.

Copy the example file to create your own:

```bash
cp .env.example .env
```

| Variable | Default | Purpose |
| --- | --- | --- |
| `JWT_SECRET` | demo key | HMAC key for signing tokens. **Minimum 32 characters**, enforced at startup |
| `DB_NAME` | `demobank` | Schema name |
| `DB_ROOT_PASSWORD` | demo value | MySQL `root`, used only for initial provisioning |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `demobank_app` | Runtime account — **DML only**, cannot alter the schema |
| `DB_MIGRATOR_USER` / `DB_MIGRATOR_PASSWORD` | `demobank_migrator` | Migration account — DDL, used only during startup |
| `WEB_PORT` | `8080` | Host port for the web entrypoint |
| `MYSQL_HOST_PORT` | `3307` | Host port for MySQL, bound to `127.0.0.1` only |
| `SPRING_PROFILES_ACTIVE` | `demo` | `demo` enables the API docs; `prod` locks the deployment down |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Genuine cross-origin callers, i.e. the Angular dev server. In Docker the front end proxies `/api`, so requests come from the application's own origin and are allowed without appearing here |

Generate a real signing key with:

```bash
openssl rand -base64 48
```

### Profiles

| Profile | API docs | CSP | Log level | Demo credentials |
| --- | --- | --- | --- | --- |
| `demo` (default) | enabled | relaxed enough for Swagger UI | `DEBUG` for the app | warned about loudly |
| `prod` | **disabled** | strict (`default-src 'none'`) | `WARN` | **refuses to boot** |

### MySQL accounts

The stock MySQL image creates one application user with full rights on the
schema. This project does not use it. Instead,
`db/init/01-create-db-users.sh` provisions two accounts with separated
privileges:

- **`demobank_migrator`** — `SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP,
  INDEX, REFERENCES`. Used **only by Flyway, only during startup**.
- **`demobank_app`** — `SELECT, INSERT, UPDATE, DELETE`. This is the account the
  running application uses.

The consequence is the point: a SQL-injection flaw in the application cannot
`DROP TABLE`, cannot read `information_schema` beyond what it needs, and cannot
create a user. The capability simply is not there. Hibernate is configured with
`ddl-auto: validate`, so it may only *verify* the schema — never generate DDL.

---

## 🏗 Architecture

```
                        ┌──────────────────────────────────────┐
   browser ──────────▶  │  nginx  (nginx-unprivileged, uid 101) │
   :8080                │  • serves the compiled Angular app    │
                        │  • reverse-proxies /api → backend     │
                        │  • rate-limits POST /api/auth/login   │
                        │  • adds CSP and the other headers     │
                        └───────────────┬──────────────────────┘
                                        │ private Docker network
                                        ▼
                        ┌──────────────────────────────────────┐
                        │  Spring Boot API  (uid 10001)         │
                        │  • stateless JWT authentication       │
                        │  • ownership checks on every read     │
                        │  • transfers in one DB transaction    │
                        └───────────────┬──────────────────────┘
                                        │ JDBC (DML only)
                                        ▼
                        ┌──────────────────────────────────────┐
                        │  MySQL 8.4                            │
                        │  • Flyway migrations at startup (DDL) │
                        │  • CHECK constraints as a backstop    │
                        └──────────────────────────────────────┘
```

The browser talks to a **single origin**. That is why the Content-Security-Policy
can stay at `connect-src 'self'`, why there is no CORS configuration to get
wrong in the deployed stack, and why the API needs no published port.

---

## 🗄 Data model

```
users ──1:N──▶ accounts ──1:N──▶ movements
  │                │                  ▲
  │                └──────┐           │
  └──1:N──▶ transfers ────┴───────────┘
              (source / destination / audit)
```

**`users`** — email (unique, case-insensitive), `password_hash`, full name, role,
enabled flag, timestamps. The password column is named `password_hash` so that no
future reader can mistake it for a plaintext secret, and it is exactly
`VARCHAR(60)` — the fixed width of a BCrypt hash — so a longer value cannot be
stored even by mistake.

**`accounts`** — account number (unique, IBAN-like), owner, currency,
`DECIMAL(19,2)` balance, and a `version` column for optimistic locking.

**`movements`** — one statement line: account, timestamp, description, type
(`DEBIT`/`CREDIT`), amount, and `balance_after` (a snapshot of the balance right
after the line was applied, so a statement is reproducible without replaying the
whole history).

**`transfers`** — the audit record of one operation: a public UUID reference to
whom it belongs, both accounts, who initiated it, the amount, the status and the
timestamp.

Constraints and indexes are not decorative:

| Object | Why |
| --- | --- |
| `uk_users_email` | One account per address |
| `fk_accounts_owner`, `fk_movements_account`, … all `ON DELETE RESTRICT` | A customer or an account cannot be deleted while history refers to it. Financial history must not develop holes. |
| `idx_movements_account_date (account_id, executed_at DESC, id DESC)` | Serves the statement query — including its ordering — from the index |
| `chk_accounts_balance_non_negative` | The database itself refuses an overdraft |
| `chk_movements_amount_positive`, `chk_transfers_amount_positive` | No zero or negative movements can exist |
| `chk_transfers_distinct_accounts` | A transfer to oneself is impossible at the storage layer |
| `chk_movements_type`, `chk_transfers_status`, `chk_accounts_currency` | Only the values the application understands |

### Why `DECIMAL` and not `DOUBLE`

Money is `DECIMAL(19,2)` in the database and `BigDecimal` in Java, all the way
through. Binary floating-point types cannot represent `0.10` exactly, and the
error accumulates over repeated operations until a balance is off by a cent. In
the same spirit, the API serialises money as a **JSON string** (`"2540.75"`,
never `2540.75`): a JSON number would be parsed into an IEEE-754 double by the
browser before any application code ran. The Angular client does its arithmetic
in `BigInt` cents and never in `number`.

---

## 🔌 API reference

Base URL in the Docker deployment: `http://localhost:8080/api`.
Interactive documentation: <http://localhost:8080/swagger-ui.html>.

All requests and responses are JSON, UTF-8. Money is always a decimal **string**.

### Error shape

Every failure — from any endpoint — has the same body:

```json
{
  "timestamp": "2026-10-06T12:00:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "code": "INSUFFICIENT_FUNDS",
  "message": "Insufficient balance on the source account for this transfer.",
  "path": "/api/transfers",
  "fieldErrors": [
    { "field": "amount", "message": "Amount must be at least 0.01" }
  ]
}
```

`code` is stable and machine-readable; clients branch on it and never on
`message`. `fieldErrors` is present only for validation failures.

| Code | Status | Meaning |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | A field failed validation; see `fieldErrors` |
| `MALFORMED_REQUEST` | 400 | Body is not valid JSON, or contains unknown fields |
| `INVALID_PARAMETER` / `MISSING_PARAMETER` | 400 | A query parameter is malformed or absent |
| `SAME_ACCOUNT` | 400 | Source and destination are the same account |
| `CURRENCY_MISMATCH` | 400 | The two accounts do not share a currency |
| `INVALID_AMOUNT` | 400 | Amount is not positive, or has more than two decimals |
| `INVALID_DATE_RANGE` | 400 | `from` is after `to` |
| `INVALID_CREDENTIALS` | 401 | Email/password pair does not authenticate |
| `ACCESS_DENIED` | 403 | Authenticated but not permitted |
| `RESOURCE_NOT_FOUND` | 404 | No such resource **for this customer** — a foreign row and a missing one are reported identically |
| `NOT_FOUND` | 404 | The URL itself matches no route |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP verb |
| `INSUFFICIENT_FUNDS` | 422 | The balance cannot cover the transfer |
| `TOO_MANY_REQUESTS` | 429 | Too many failed sign-in attempts |
| `INTERNAL_ERROR` | 500 | Unexpected failure; the detail is in the server log only |

---

### `POST /api/auth/login` — public

The only endpoint that does not require a token.

```json
{ "email": "alice@demo.local", "password": "Demo1234!" }
```

**200**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 1800,
  "user": { "id": 1, "email": "alice@demo.local", "fullName": "Alice Rossi", "role": "ROLE_CUSTOMER" }
}
```

**401** identical for an unknown address, a wrong password and a disabled
account. **400** for a malformed body. **429** after five failures within the
window — including for the *correct* password, otherwise the throttle would be
pointless.

---

### `GET /api/auth/me` — authenticated

Returns the identity behind the presented token. Lets a single-page application
rehydrate its session, and gives the test suite a cheap way to assert which
principal a token resolves to.

```json
{ "id": 1, "email": "alice@demo.local", "fullName": "Alice Rossi", "role": "ROLE_CUSTOMER" }
```

---

### `GET /api/accounts` — authenticated

The accounts of the authenticated customer, ordered by account number.

```json
[
  {
    "id": 1,
    "accountNumber": "IT60X0542811101000000123456",
    "ownerName": "Alice Rossi",
    "currency": "EUR",
    "balance": "2540.75"
  }
]
```

There is no user parameter anywhere in this contract. The identity comes from the
token, so there is no field a client could edit to ask for somebody else's data.

---

### `GET /api/accounts/{id}` — authenticated

One account, same shape. **404** if the account does not exist **or belongs to
another customer** — the two cases are deliberately indistinguishable.

---

### `GET /api/accounts/{id}/movements` — authenticated

The statement, newest first.

| Parameter | Type | Description |
| --- | --- | --- |
| `from` | `yyyy-MM-dd` | Inclusive start date (optional) |
| `to` | `yyyy-MM-dd` | Inclusive end date (optional) |
| `limit` | integer | Max rows (default 200, capped at 500) |

Both bounds are inclusive, because users think in calendar days.

```json
[
  {
    "id": 4,
    "executedAt": "2026-10-03T09:15:00Z",
    "description": "Supermarket",
    "type": "DEBIT",
    "amount": "259.25",
    "balanceAfter": "2540.75",
    "transferReference": null
  }
]
```

`amount` is always a positive magnitude and `type` carries the direction, so the
client never has to infer a sign convention.

---

### `POST /api/transfers` — authenticated

```json
{
  "sourceAccountId": 1,
  "destinationAccountId": 2,
  "amount": "100.00",
  "description": "Rent share"
}
```

`amount` may be sent as a JSON string (what the web client does, so the value
never passes through a JavaScript float) or as a JSON number. The user's identity
is **not** a field of this payload: it comes from the token, and accepting it
from the body would let a customer move somebody else's money by editing the
JSON.

**201**

```json
{
  "reference": "3f8a1c9e-5b7d-2460-af13-c8e6d94b7205",
  "executedAt": "2026-10-06T12:00:00Z",
  "amount": "100.00",
  "currency": "EUR",
  "description": "Rent share",
  "status": "COMPLETED",
  "sourceAccount":      { "id": 1, "balance": "2440.75", ... },
  "destinationAccount": { "id": 2, "balance": "280.00",  ... }
}
```

Both new balances come back, so the UI can refresh without a second round trip.
`reference` is a random UUID rather than a sequential id: a sequence would leak
how much business the bank does and invite enumeration.

**Refusals** — `400` same account / currency mismatch / invalid amount,
`404` an account that does not exist or is not the caller's, `422` insufficient
funds. In **every** one of these cases nothing at all is written.

---

## 🔒 Security

This section is the heart of the project. Each control is listed with the threat
it addresses and, where a trade-off was made, what was given up.

### Authentication

**Passwords are only ever stored as BCrypt hashes** (cost factor 12, unique
random salt per password, ~250 ms per verification). BCrypt is deliberately slow
and salted, so a stolen dump cannot be attacked with a rainbow table and offline
guessing is expensive. The column is `VARCHAR(60)` — the exact width of a BCrypt
hash — so nothing longer can be stored even by accident. The entity field is
named `passwordHash`, and `User.toString()` omits it, so the hash cannot reach a
log by way of an innocuous log statement.

**The login endpoint reveals nothing about who has an account.** Three measures
combine:

- An unknown address and a wrong password return *the same status, the same code
  and the same message*. The test suite asserts that the two responses are equal.
- **Timing is equalised.** When the address is unknown, the service still
  performs one BCrypt comparison against a throwaway hash. Without it,
  "unknown email" would return in microseconds while "wrong password" took a
  quarter of a second — a trivially measurable difference that enumerates the
  customer base. The throwaway hash is *encoded at startup* rather than written
  out as a literal, and that detail is the whole point: a hand-written BCrypt
  string is easy to get one character wrong, and a malformed one makes the
  encoder reject it and return `false` without hashing anything, silently
  restoring the oracle while every comment still claims otherwise. Encoding a
  random value cannot drift, and a test asserts the unknown-address path still
  costs a real hash comparison.
- A **disabled account** is reported exactly like a wrong password.

**Brute force is throttled on two independent axes.** Only failed attempts are
counted, and a success clears the counter — a user who mistypes twice and then
succeeds is not penalised.

- `user:<email>` stops an attacker trying thousands of passwords against one
  account from many machines.
- `ip:<address>` stops one machine spraying a single password across thousands
  of accounts.

The throttle is applied **before** the password is examined, so a blocked attempt
is refused even when it carries the correct password. There is a second,
independent limiter in nginx, in front of the JVM.

**Tokens** are JWTs signed with HMAC-SHA256. The signing key must be at least 32
characters or the application refuses to start. Tokens carry an issuer, a
`jti`, and a short 30-minute lifetime, and verification checks the signature, the
expiry, the issuer and the clock skew. A role that the application does not
recognise is rejected rather than mapped to something permissive. The token is
never logged.

### Authorisation

**There is no user id anywhere in the API contract.** Every query is scoped by
the owner id taken from the verified token, so "ask for someone else's data" is
not an operation the API can express.

**A foreign resource and a missing one are indistinguishable** — both return
`404`, never `403`. A `403` would confirm that the row exists, which is already a
disclosure, and it turns the id space into something worth enumerating. The test
suite walks the whole neighbourhood of real ids and asserts that every one that
is not the caller's answers identically.

**Method-level `@PreAuthorize` repeats** what the URL rules already enforce. If
somebody later edits a URL rule in `SecurityConfig`, the controller methods do
not silently become public.

**Deny by default.** The filter chain ends with `anyRequest().denyAll()`. A new
controller added next year is unreachable until somebody deliberately opens a
rule for it — the opposite of "public unless I remember to protect it".

### Transport and session

**Stateless.** No HTTP session is created or read, so there is no session
identifier to steal, fixate or replay.

**CSRF protection is disabled, and that is safe here** — precisely because
authentication never comes from a cookie. CSRF exists because browsers attach
cookies to cross-site requests automatically; this API reads an `Authorization`
header, which an attacker's page cannot set. nginx additionally strips the
`Cookie` header before forwarding, so a browser-attached credential has no path
into the backend at all. *If a cookie-based scheme is ever introduced, this must
be revisited.*

**CORS** is an explicit allow-list of origins, never `*`, with credentials
disabled. Genuine cross-origin calls must appear in it; a page on another site
cannot read a response from this API.

One case is deliberately *not* treated as cross-origin: **a request addressed to
its own origin is always allowed.** That is not a relaxation, it is the removal
of a false positive that is easy to get wrong, and this project got it wrong
first. Browsers attach an `Origin` header to *every* POST, same-origin ones
included. In the Docker deployment nginx serves the SPA and proxies the API on
one port, so every login and every transfer arrives as
`Origin: http://localhost:8080` — an origin absent from an allow-list written
for the Angular dev server, and therefore refused with
`403 Invalid CORS request`. The application was completely unusable from a
browser while the API test suite stayed green, because no test sent an `Origin`
header and neither does `curl`. `CorsPolicyTest` now pins both halves of the
rule: same-origin must pass, everything else must still be refused.

**Security headers are set once per response, by whichever layer owns it.**
The API sets its own through the Spring Security filter chain — a strict CSP
(`default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`
in `prod`, relaxed just enough for Swagger UI under `demo`),
`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`, a restrictive `Permissions-Policy`,
`Cross-Origin-Opener-Policy` and `Cross-Origin-Resource-Policy`. nginx sets the
same family for the SPA and the static assets it serves itself, with a CSP that
permits the application's own scripts and styles.

The two sets are deliberately *not* both applied to the same response. Two CSP
headers combine into their intersection, and the resulting policy is neither of
the two written in the configuration files — a reviewer reading either layer
would be reading a policy that is not in force. nginx adds only `X-Robots-Tag`
to proxied responses, which the API does not set.

**`Cache-Control: no-store`** on API responses: personal financial data must not
survive in a browser cache, a proxy cache or the disk of a shared machine, so
that pressing "back" after signing out cannot resurrect a statement.

**HSTS** is configured in both layers but only in force over HTTPS. The API
sends it on secure requests, and it is present but commented out in nginx;
browsers ignore the header on plain HTTP, which is what the demo is served over.
Both are ready for a TLS deployment.

### Data integrity

**A transfer is one transaction.** The two balance updates, the audit record and
the two statement lines are written inside a single database transaction; every
failure path throws, and a throw rolls the whole thing back. There is no
partial-success branch and no "save what we can" fallback. The test suite proves
this by injecting a failure *after* the balances have already been changed and
asserting that the database is exactly where it started — including the
optimistic-locking counter.

**Rows are locked before the balance is read.** Two requests spending from the
same account at the same moment must not both see the old balance and both
succeed. The transfer takes a pessimistic write lock (`SELECT … FOR UPDATE`) on
both accounts, in ascending id order so that two transfers running in opposite
directions cannot deadlock. The second transaction waits, then reads the updated
balance and is correctly refused.

**Three independent layers protect the balance:** the explicit check in the
service (which produces a precise error), the guard inside `Account.debit()`, and
the `CHECK` constraint on the column. The duplication is deliberate — the first
two produce good error messages, the third is the backstop that holds even if the
code above is bypassed.

**Amounts are normalised without rounding.** `setScale(2, UNNECESSARY)` throws
rather than rounding, so `10.005` is refused instead of silently becoming
`10.01`. Inventing or destroying a fraction of a cent is exactly what makes
financial software untrustworthy.

**Statement reads are capped** at 500 rows per request. Without a cap, one call
could ask for an entire account history and turn a read endpoint into a memory
and bandwidth amplifier.

### Input handling

**Unknown JSON fields are rejected, not ignored.** Jackson is configured with
`fail-on-unknown-properties`. A client trying to smuggle `"role": "ROLE_ADMIN"`
or `"status": "REJECTED"` into a payload gets a `400` rather than having the
field quietly dropped — or worse, bound.

**Every field is validated before the service layer is reached**, so a malformed
request costs one cheap check instead of a database round trip. Limits mirror the
column widths, so an over-long value is a clear `400` rather than a truncation or
a confusing `500` from the database.

**Nothing internal crosses the boundary.** The catch-all handler returns one
generic sentence and logs the stack trace server-side. Stack traces, SQL
fragments, class names and third-party exception messages never reach a client —
a permanent invitation to leak connection strings and file paths. Framework error
pages are disabled (`whitelabel.enabled: false`, `include-message: never`).

**Request size is bounded** at several layers: nginx (256 kB), Tomcat (64 kB
form post) and the password length itself. The cheapest denial of service against
a login endpoint is to send work that is expensive to reject — and BCrypt at cost
12 is deliberately expensive. Passwords are capped at 72 bytes because BCrypt
ignores anything beyond that, so a longer password would mean two different
strings authenticating the same account.

### Configuration and deployment

**Least privilege in the database.** Two accounts: one that owns DDL and is used
only by Flyway during startup, one with DML only that the running application
uses. A SQL injection cannot drop a table or create a user, because the
capability is not there.

**Fail closed on misconfiguration.** `StartupSecurityCheck` refuses to boot under
the `prod` profile if the JWT key or a database password is still one of the demo
values published in this repository. The most dangerous misconfiguration in a
token-based API is not a crash — it is a service that starts happily while
signing tokens with a public key, because the logs look perfectly normal. Under
any other profile it logs a prominent warning.

**`open-in-view` is disabled**, so a request never holds a database connection
while rendering — and a lazy-loading mistake fails loudly in development instead
of silently working in production.

**Error verbosity is turned off** globally; the actuator exposes only `health`,
and only with `show-details: never`.

**Containers are hardened.** Both application images run as **non-root** (uid
10001 for the API, uid 101 for nginx), with `read_only: true` root filesystems
and explicit `tmpfs` mounts for the paths that genuinely need writing,
`cap_drop: ALL`, `no-new-privileges`, and no package managers or build tools in
the runtime layer. Multi-stage builds mean the compiler, the npm cache and the
full source tree are simply not present in the image that runs.

**Only one port is published.** The API and MySQL are unreachable from outside
the Docker network; MySQL's host mapping is bound to `127.0.0.1` and exists only
so you can inspect the demo data with a local client. This removes the entire
class of "internal service accidentally exposed" problems.

**`server_tokens off`** and no `Server` banner: the deployment advertises
nothing about its stack.

### Front end

**The token is held in memory only** — never in `localStorage` or
`sessionStorage`. Any script running on the page could read those, so persisting
the token would convert a single XSS into a stolen session. The cost is real and
accepted: a page reload signs the user out.

**Money is never a JavaScript `number`.** The models type amounts as `string`,
the display pipe formats them with `Intl.NumberFormat` and falls back to the raw
string when a value exceeds `Number.MAX_SAFE_INTEGER`, and the two places that do
arithmetic (the dashboard total and the "sufficient funds" check) work in
`BigInt` cents.

**The auth interceptor attaches the token only to same-origin `/api` calls**, so
it can never be sent to a third-party host.

**The error interceptor normalises every failure** into the API error shape and,
on a `401` that is not a login attempt, clears the session and redirects to the
login page — so an expired token cannot leave the UI in a half-authenticated
state.

**The route guard is usability, not security.** It stops an anonymous user from
seeing an empty dashboard; it protects nothing. Every rule it appears to enforce
is enforced again, and for real, on the server.

### Deliberate trade-offs

Being explicit about these is part of the design:

| Decision | Cost | Why it was accepted |
| --- | --- | --- |
| Token in memory, not `localStorage` | A page reload signs the user out | Removes the easiest path from XSS to session theft. A production system would use a short-lived access token plus a rotating refresh token in an `HttpOnly`, `SameSite` cookie. |
| Login throttle is in-process memory | Does not survive a restart, and is per-replica | Right for a single-instance didactic deployment: no external dependency, no shared state to protect. A multi-replica deployment must move it to a shared store, otherwise the effective limit is multiplied by the number of replicas. The map is bounded so a flood of distinct keys cannot exhaust memory. |
| No token revocation list | A stolen token is valid until it expires | Keeps the API stateless. The 30-minute lifetime bounds the exposure. Real revocation needs shared state, which is a genuine feature with a genuine cost. |
| Logout is client-side only | The token remains technically valid | There is nothing server-side to invalidate in a stateless design. |
| Sign-in returns the same error for every failure | Slightly worse usability | The alternative is an enumeration oracle. |
| Demo credentials are in the repository | The demo is trivially accessible | It is a teaching artefact; no real data is at stake. The `prod` profile refuses to start with them. |

---

## 🧪 Testing

### Backend

The suite covers the four areas that matter: **authentication**,
**authorisation**, **validation** and **transfer atomicity**.

```bash
# With a local Maven and JDK 21
cd backend
mvn test

# Without installing anything (recommended on Windows)
docker run --rm \
  -v "$PWD/backend:/app" -v "demobank-m2:/root/.m2" -w /app \
  maven:3.9-eclipse-temurin-21 mvn -B test
```

| Class | What it covers |
| --- | --- |
| `AuthApiTest` | Sign-in success and failure, the enumeration guarantees (identical responses, generic message, no hash in the payload, and a real BCrypt comparison on the unknown-address path), validation, throttling and its reset, token tampering, and the response hardening headers |
| `AccountAccessApiTest` | A customer sees only their own accounts; foreign vs. missing accounts are indistinguishable; id enumeration reveals nothing; a foreign statement is refused even when it has data |
| `MovementApiTest` | Newest-first ordering, inclusive date bounds, reversed and malformed ranges, the row cap |
| `TransferApiTest` | The happy path and its full audit trail; and every refusal (insufficient funds, same account, foreign account, currency mismatch, every validation rule) checked against a **snapshot of the whole database state** taken beforehand |
| `TransferAtomicityTest` | An injected failure after the balances have been adjusted leaves no trace at all; two simultaneous transfers cannot spend the same money twice |
| `CorsPolicyTest` | A browser posting to its own origin is not mistaken for a cross-origin caller, at the front door and behind the proxy; another site is still refused on reads, writes and preflights; an origin that merely *starts with* an allowed one is refused |

Tests are full-stack and black-box: a real Spring context, the real security
filter chain, real JWT verification, a real database. Nothing is stubbed except
in the one atomicity test, where a repository spy is the only way to produce a
failure *between* two statements. They are deliberately **not** annotated with
`@Transactional` — a test-managed transaction that always rolls back would hide
exactly the behaviour being examined.

**An honest limitation.** The suite runs against **H2 in memory**, not MySQL. It
is fast, hermetic and needs no infrastructure, but it cannot prove that the
Flyway migrations run or that the MySQL `CHECK` constraints bite — H2 would not
accept the MySQL DDL verbatim. The migrations are verified separately by starting
the real stack, where `ddl-auto: validate` makes Hibernate fail loudly if the
entities and the migrated schema disagree.

### Front end

```bash
cd frontend
npm install
npm test           # Karma + Jasmine
npm run build      # production build
```

The front-end tests are smoke tests for the application shell; the behaviour that
actually enforces security lives on the server, and that is where it is tested.

**What this level of test cannot catch, and did not.** These tests mock HTTP, so
they prove the component logic and nothing about the wire. Two defects lived
precisely in that gap and were found only by driving the running stack in a real
browser: the CORS policy refused every same-origin POST, so the application
could not be logged into at all, and nginx served Swagger UI's scripts and
styles from its own disk, so the documentation page rendered blank. Both passed
the entire backend suite, because neither `curl` nor `MockMvc` sends an `Origin`
header or evaluates a regex `location`. A browser-driven smoke test over the
deployed stack is the cheapest thing that would have caught them.

---

## 🔁 CI/CD pipeline

Everything above is checked automatically on every push. The pipeline is one
file, `.github/workflows/ci.yml`, and it runs four jobs:

```
validate ─────────────┐
backend-tests ────────┼──► images (matrix: backend | frontend)
frontend-tests ───────┘        build → Trivy → push
```

| Job | What it does |
| --- | --- |
| `validate` | `docker compose config -q` — which parses the file *and* interpolates every `${VAR:-default}` — plus BuildKit's Dockerfile linter (`docker buildx build --check`) on both contexts |
| `backend-tests` | `mvn -B -ntp test` on JDK 21, the same major version the image runs. The full suite, on H2, with no database and no secret. The Surefire reports are uploaded when it fails |
| `frontend-tests` | `npm ci`, the Karma smoke tests in headless Chrome, then the production build — which is where AOT compilation and the bundle budgets are enforced |
| `images` | Builds each image in the runner, scans it, and only then publishes it. The two are separate steps on purpose (see below) |

### When it runs

| Event | Test | Build + scan | Publish |
| --- | --- | --- | --- |
| Pull request | ✅ | ✅ | ❌ — so a PR from a fork needs no secret at all |
| Push to `main` | ✅ | ✅ | ✅ `latest`, `sha-<short>` |
| Tag `v1.0.0` | ✅ | ✅ | ✅ `1.0.0`, `1.0`, `latest`, `sha-<short>` |
| Manual run | ✅ | ✅ | only when the `push` box is ticked |

### The scan is a gate, not a report

Trivy inspects the image **before** it is published: a CRITICAL or HIGH
vulnerability with a fix available fails the job, and the push step is never
reached. `ignore-unfixed: true` keeps the gate honest rather than merely green —
an unfixed CVE in a base image is not actionable, and the answer to it is a newer
base image, not a lower threshold.

Two details are what make the gate mean what it says:

* **The image is scanned under a local-only tag** (`demobank-local/backend:scan`).
  Trivy falls back to inspecting a registry, so scanning a pushable tag such as
  `backend:latest` could quietly examine the *previous* publication and pass
  while looking at the wrong artifact.
* **Nothing is rebuilt between the scan and the push.** Both Dockerfiles run
  `apk --no-cache upgrade`, so a rebuild is not reproducible; a "rebuild and
  push" step could publish bytes that were never scanned.

The accepted cost: the manifest digest is the one the local daemon produces, not
the one `build-push-action --push` would have produced, and it carries no
provenance attestation. The image content is identical. A registry-side
attestation would require scanning *after* the push — that is, accepting a window
in which a vulnerable image is already published.

### Required repository secrets

| Secret | Value |
| --- | --- |
| `DOCKERHUB_USERNAME` | the Docker Hub account that owns the two images |
| `DOCKERHUB_TOKEN` | a Docker Hub **access token** with *Read & Write* — not the account password |

Add them under *Settings → Secrets and variables → Actions → New repository
secret*. The image namespace is taken from `DOCKERHUB_USERNAME`, so there is
nothing to edit in the workflow: no other check needs a secret, and the whole
test path runs unchanged on a fork.

### Publishing a release

```bash
git tag v1.0.0
git push origin v1.0.0
```

`latest` follows the repository's default branch and every `v*` tag; `sha-<short>`
is published on every push.

### Running the published images

```bash
BACKEND_IMAGE=<dockerhub-user>/backend:latest \
FRONTEND_IMAGE=<dockerhub-user>/frontend:latest \
docker compose pull && docker compose up -d --no-build
```

`--no-build` is not optional: the compose file still declares `build:`, so
without it compose would rebuild from source whenever the image is missing
locally. The same two variables live in `.env.example`.

### Reproducing every check locally

```bash
docker compose config -q                                        # validate
docker compose --profile tests run --rm --build backend-tests   # backend suite
docker buildx build --check ./backend                           # Dockerfile lint
cd frontend && npm ci && npm run build -- --configuration production
npm test -- --watch=false --browsers=ChromeHeadlessNoSandbox    # needs Chrome
trivy image --severity CRITICAL,HIGH --ignore-unfixed <image>   # the gate
```

`ChromeHeadlessNoSandbox` is a launcher the Angular Karma builder defines itself,
which is why the project needs no `karma.conf.js`. `--watch=false` is what stops
the run from sitting there watching files until CI kills it.

### Supply-chain note

Every `uses:` is pinned to a full commit SHA, with the readable version in a
trailing comment. That is not ceremony: on 19 March 2026 the tags of
`aquasecurity/trivy-action` from 0.0.1 to 0.34.2 were rewritten to deliver a
credential stealer (CVE-2026-33634). A workflow saying `@v0.34.0` would have run
it; one pinned to a commit could not. Dependabot understands SHA pins and raises
the bump as an ordinary pull request.

The jobs also pin `ubuntu-24.04` instead of `ubuntu-latest`: GitHub is migrating
that label to Ubuntu 26, and the Chrome, Maven and containerd versions underneath
would otherwise change without a single commit in this repository.

The workflow itself is checked with two independent tools, because a pipeline is
code too:

```bash
docker run --rm -v "$PWD:/repo" rhysd/actionlint:latest /repo/.github/workflows/ci.yml
docker run --rm -v "$PWD:/repo" ghcr.io/woodruffw/zizmor:latest /repo
```

`actionlint` validates the schema, the expressions and the shell in every `run:`
block. `zizmor` audits the security posture: it reports **no findings** today,
apart from two `cache-poisoning` findings on the Maven and npm caches that are
suppressed inline with the reasoning next to them — those caches belong to jobs
that publish nothing, and the image that *is* published is built in another job,
from a Docker-level cache that pull requests can read but never write.

`persist-credentials: false` on every checkout comes from the same audit: the
workflow only reads the repository, and several of its steps run third-party code
(Maven plugins, npm packages, the build itself) that would otherwise find the job
token sitting in `.git/config`.

---

## 🛠 Running without Docker

Useful while developing, when you want hot reload.

### 1. Database

The simplest route is to start only MySQL from the compose file:

```bash
docker compose up -d mysql
```

It listens on `127.0.0.1:3307` with the schema, both accounts and the demo data
already provisioned. (Any MySQL 8 server works; create the schema and the two
accounts as described in [Configuration](#️-configuration).)

### 2. Backend

```bash
cd backend

# The API listens on 8080 by default, which is also the port the Docker front
# end publishes. Move it to 8081 locally so the two can coexist and so it
# matches the target the Angular dev server proxies to.
export SERVER_PORT=8081

export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3307/demobank?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8'
export DB_APP_USER=demobank_app
export DB_APP_PASSWORD=demo_app_pwd_change_me
export DB_MIGRATOR_USER=demobank_migrator
export DB_MIGRATOR_PASSWORD=demo_migrator_pwd_change_me
export JWT_SECRET='CHANGE_ME_demo_only_3f8a1c9e5b7d2460af13c8e6d94b7205'
export SPRING_PROFILES_ACTIVE=demo

mvn spring-boot:run
```

The API starts on <http://localhost:8081> and runs the Flyway migrations on the
way up.

### 3. Front end

```bash
cd frontend
npm install
npm start
```

The dev server runs on <http://localhost:4200> and proxies `/api` to
`http://localhost:8081` using `proxy.conf.json`, so the browser still sees a
single origin. The API does still see an `Origin: http://localhost:4200` header
on every POST — browsers send one even for same-origin requests — which is why
that origin is in the default allow-list. Remove it and the dev server can no
longer sign in.

---

## 📁 Project structure

```
.
├── .github/workflows/
│   └── ci.yml                    # test, build, scan and publish the images
│
├── docker-compose.yml            # the whole stack
├── .env.example                  # every configurable value, documented
│
├── db/init/
│   └── 01-create-db-users.sh     # provisions the least-privilege MySQL accounts
│
├── backend/
│   ├── Dockerfile                # multi-stage: Maven build → JRE alpine, non-root
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/demobank/
│       │   ├── config/           # security configuration and its properties
│       │   ├── domain/           # JPA entities
│       │   ├── dto/              # request/response records and the error shape
│       │   ├── exception/        # typed API exceptions + the global handler
│       │   ├── repository/       # Spring Data repositories
│       │   ├── security/         # JWT service and filter, throttling, startup check
│       │   ├── service/          # business logic, transaction boundaries
│       │   └── web/              # REST controllers
│       ├── main/resources/
│       │   ├── application.yml         # base configuration
│       │   ├── application-demo.yml    # demo profile
│       │   ├── application-prod.yml    # hardened profile
│       │   └── db/migration/           # Flyway: schema + demo data
│       └── test/                       # the integration suite
│
└── frontend/
    ├── Dockerfile                # multi-stage: Node build → nginx-unprivileged
    ├── nginx/                    # site config, proxy and security-header snippets
    └── src/app/
        ├── core/                 # models, services, interceptors, guard
        ├── features/             # login, dashboard, account detail, transfer
        └── shared/               # money pipe, notifications
```

---

## 🔧 Troubleshooting

**`docker compose up` hangs on the backend**
MySQL takes 30–60 s to initialise on a cold volume. The API waits for its
healthcheck, so this is expected. Watch progress with
`docker compose logs -f mysql`.

**The page loads but every call returns 401**
The token is held in memory, so a reload signs you out — that is by design, not
a bug. Sign in again.

**`Ports are not available: 8080`**
Something else is using the port. `WEB_PORT=9090 docker compose up` and open
<http://localhost:9090>.

**`Refusing to start with the 'prod' profile because demo credentials are in use`**
Working as intended. Set a real `JWT_SECRET`, `DB_APP_PASSWORD` and
`DB_MIGRATOR_PASSWORD`, or drop back to the `demo` profile.

**I want to inspect the database**
It is published on `127.0.0.1:3307` only:

```bash
mysql -h 127.0.0.1 -P 3307 -u demobank_app -p demobank
```

Log in as `demobank_app` rather than `root` and you will see the least-privilege
design for yourself: `SELECT` works, `DROP TABLE` does not.

**I changed the schema and now the API will not start**
`ddl-auto: validate` is doing its job: the entities and the migrated schema
disagree. Add a new Flyway migration rather than editing an existing one —
`validate-on-migrate: true` will refuse a migration file that was modified after
being applied, which is exactly the protection you want.

---

## ⚠️ Known limitations

This is a teaching project, and these are the boundaries of what it claims:

- **Single instance.** The login throttle and the rate limiting are in-process.
  Horizontal scaling would need a shared store.
- **No token revocation.** Signing out is client-side; a stolen token stays valid
  until it expires.
- **No password change, registration or account creation.** Customers and
  accounts come from the seed migration.
- **Transfers are limited to one customer's own accounts.** Transferring to a
  third party would need a way to name a beneficiary without leaking the account
  directory — a genuinely interesting problem, and out of scope here.
- **No cross-currency transfers.** Converting would need a rate and a rounding
  policy; silently treating 100 USD as 100 EUR would be worse than refusing.
- **The test suite runs on H2, not MySQL**, so it does not exercise the Flyway
  migrations or the `CHECK` constraints. See [Testing](#-testing).
- **HTTP, not HTTPS.** HSTS is configured and commented out, ready for TLS. On a
  real network the token would be readable in transit without it.
- **A TLS terminator in front of nginx needs one extra line.** nginx sets
  `X-Forwarded-Proto` from its own scheme, which is `http`, so behind an
  upstream HTTPS proxy the API would reconstruct `http://host` while the browser
  said `https://host` — the same-origin check would fail and every POST would be
  refused. Terminating TLS in front means changing that directive to honour the
  header the terminator already sends (`map $http_x_forwarded_proto` is the
  usual way). Terminating it *in* nginx needs no change.
