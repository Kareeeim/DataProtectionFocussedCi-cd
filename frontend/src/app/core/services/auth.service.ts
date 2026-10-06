import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { AuthenticatedUser, LoginResponse } from '../models/api.models';

/**
 * Holds the session: the access token and the identity it belongs to.
 *
 * ## Where the token lives, and why
 *
 * The token is kept **in memory only** - a signal inside this service. It is
 * never written to `localStorage` or `sessionStorage`.
 *
 * That is a deliberate security trade-off. Browser storage is readable by any
 * script running on the page, so a single cross-site scripting flaw anywhere in
 * the application (or in a third-party dependency) is enough for an attacker to
 * read a persisted token and use it from their own machine, for as long as it
 * remains valid. A token that only ever exists in a JavaScript variable dies
 * with the tab and is not recoverable from a stored artefact.
 *
 * The cost is real and worth stating: reloading the page logs the user out,
 * because there is nothing left to restore the session from. Production
 * applications usually solve that with a short-lived access token in memory
 * plus a long-lived refresh token in an `HttpOnly`, `Secure`, `SameSite`
 * cookie - the cookie is invisible to JavaScript, so it survives a reload
 * without being exposed to XSS. That scheme needs a server-side refresh
 * endpoint and rotation logic, which is out of scope for this demo, so the
 * safer of the two simple options is the one implemented here.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {

  private readonly http = inject(HttpClient);

  /** Current token, or `null` when signed out. */
  private readonly tokenSignal = signal<string | null>(null);

  /** Identity attached to the current token. */
  private readonly userSignal = signal<AuthenticatedUser | null>(null);

  /** Read-only view of the token, for the HTTP interceptor. */
  readonly token = this.tokenSignal.asReadonly();

  /** Read-only view of the signed-in user, for the UI. */
  readonly user = this.userSignal.asReadonly();

  /** True while a session is active. */
  readonly isAuthenticated = computed(() => this.tokenSignal() !== null);

  /**
   * Exchanges credentials for an access token.
   *
   * The token is stored only after the response arrives, so a failed login
   * cannot leave a half-initialised session behind.
   */
  login(email: string, password: string): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>('/api/auth/login', { email, password })
      .pipe(
        tap((response) => {
          this.tokenSignal.set(response.accessToken);
          this.userSignal.set(response.user);
        }),
      );
  }

  /**
   * Ends the session locally.
   *
   * There is no server call: the API is stateless and holds nothing to
   * invalidate. Dropping the token is what ends the session - which is also why
   * the token lifetime is kept short.
   */
  logout(): void {
    this.tokenSignal.set(null);
    this.userSignal.set(null);
  }

  /** True when a token is present (used by the route guard). */
  hasToken(): boolean {
    return this.tokenSignal() !== null;
  }
}
