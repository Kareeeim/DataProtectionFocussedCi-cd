/**
 * Type definitions mirroring the REST API contract.
 *
 * A note that runs through this whole file: **monetary values are `string`, not
 * `number`.** The API serialises `DECIMAL(19,2)` columns as JSON strings, and
 * the client keeps them as strings everywhere. The moment an amount becomes a
 * JavaScript `number` it becomes an IEEE-754 double, which cannot represent
 * every decimal exactly - `0.1 + 0.2` is already `0.30000000000000004`. Strings
 * survive the round trip untouched and force every consumer to be explicit
 * about the single place where a value is turned into a display number.
 */

/** An account belonging to the authenticated customer. */
export interface Account {
  id: number;
  accountNumber: string;
  ownerName: string;
  /** ISO-4217 code, e.g. `EUR`. */
  currency: string;
  /** Exact decimal amount, e.g. `"2540.75"`. */
  balance: string;
}

/** Direction of a movement relative to its account. */
export type MovementType = 'DEBIT' | 'CREDIT';

/** One statement line. */
export interface Movement {
  id: number;
  /** ISO-8601 instant, UTC. */
  executedAt: string;
  description: string;
  type: MovementType;
  /** Always a positive magnitude; the direction is in `type`. */
  amount: string;
  /** Account balance immediately after this line. */
  balanceAfter: string;
  /** Public UUID of the originating transfer, when there is one. */
  transferReference?: string;
}

/** Payload of `POST /api/transfers`. */
export interface TransferRequest {
  sourceAccountId: number;
  destinationAccountId: number;
  /** Decimal string with at most two decimal places. */
  amount: string;
  description?: string;
}

/** Result of a completed transfer. */
export interface TransferResponse {
  reference: string;
  executedAt: string;
  amount: string;
  currency: string;
  description?: string;
  status: string;
  sourceAccount: Account;
  destinationAccount: Account;
}

/** Identity behind the current access token. */
export interface AuthenticatedUser {
  id: number;
  email: string;
  fullName: string;
  role: string;
}

/** Response of `POST /api/auth/login`. */
export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  /** Token lifetime in seconds. */
  expiresIn: number;
  user: AuthenticatedUser;
}

/** One invalid field reported by the API. */
export interface FieldViolation {
  field: string;
  message: string;
}

/**
 * The single error shape the API returns.
 *
 * `code` is the stable, machine-readable token to branch on. `message` is a
 * sentence already written for a human. The client never parses `message` to
 * decide what to do - it is only ever displayed.
 */
export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  code: string;
  message: string;
  path: string;
  fieldErrors?: FieldViolation[];
}
