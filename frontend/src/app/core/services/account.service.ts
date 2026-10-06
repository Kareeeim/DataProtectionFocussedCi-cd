import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Account, Movement } from '../models/api.models';

/**
 * Reads accounts and statements.
 *
 * Every call is relative to the current origin (`/api/...`). In the Docker
 * deployment nginx serves the application and proxies `/api` to the backend, so
 * the browser sees a single origin: no CORS preflight, no third-party cookies,
 * and a Content-Security-Policy that can stay at `connect-src 'self'`.
 *
 * Note that no method takes a user identifier. The server derives the customer
 * from the access token, and the client has no way to ask for somebody else's
 * data even if it wanted to.
 */
@Injectable({ providedIn: 'root' })
export class AccountService {

  private readonly http = inject(HttpClient);

  /** Accounts of the authenticated customer, ordered by account number. */
  listAccounts(): Observable<Account[]> {
    return this.http.get<Account[]>('/api/accounts');
  }

  /** One account; the server answers 404 if it is not the caller's. */
  getAccount(accountId: number): Observable<Account> {
    return this.http.get<Account>(`/api/accounts/${accountId}`);
  }

  /**
   * Statement of one account, newest first.
   *
   * The date filters are applied **server side**. Filtering a page of results in
   * the browser would silently produce wrong answers as soon as the account has
   * more movements than one page holds.
   *
   * @param from inclusive start date, `yyyy-MM-dd`
   * @param to   inclusive end date, `yyyy-MM-dd`
   */
  listMovements(accountId: number, from?: string, to?: string): Observable<Movement[]> {
    let params = new HttpParams();
    if (from) {
      params = params.set('from', from);
    }
    if (to) {
      params = params.set('to', to);
    }
    return this.http.get<Movement[]>(`/api/accounts/${accountId}/movements`, { params });
  }
}
