import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { ApiError } from '../models/api.models';
import { AuthService } from '../services/auth.service';
import { NotificationService } from '../services/notification.service';

/**
 * Turns API failures into something the user can act on, without ever losing
 * the state of the page they are on.
 *
 * The rules:
 *
 * - **401 on a protected call** means the token is gone, expired or rejected.
 *   The session is dropped and the user is sent to the login page, with the
 *   intended URL preserved so they land back where they were. This is *not*
 *   applied to the login call itself: a wrong password there is a normal
 *   outcome, not an expired session, and redirecting would be a loop.
 *
 * - **Every other status** is passed through to the caller unchanged, after
 *   being normalised to the API's own `ApiError` shape. Components stay in
 *   control: they can keep the form the user was filling in, highlight the
 *   offending fields and let them retry. Nothing is thrown away.
 *
 * - **Errors never become HTML.** Only `message` and `fieldErrors[].message`
 *   from the API are displayed, and they reach the DOM through Angular's
 *   escaping interpolation.
 */
export const errorInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const notifications = inject(NotificationService);

  return next(request).pipe(
    catchError((error: HttpErrorResponse) => {
      const apiError = toApiError(error, request.url);

      const isLoginCall = request.url.includes('/api/auth/login');

      if (error.status === 401 && !isLoginCall) {
        auth.logout();
        notifications.clear();
        notifications.error('Your session has expired. Please sign in again.');
        void router.navigate(['/login'], {
          queryParams: { returnUrl: router.url },
        });
      } else if (error.status === 0) {
        // status 0 means the request never produced an HTTP response: the
        // network is down or the API is unreachable.
        notifications.error(
          'The server is not reachable. Check your connection and try again.',
        );
      } else if (error.status >= 500) {
        notifications.error('An unexpected server error occurred. Please try again later.');
      }

      return throwError(() => apiError);
    }),
  );
};

/**
 * Normalises any HTTP failure into the API's `ApiError` contract.
 *
 * A non-JSON body (a proxy error page, an HTML 502 from a gateway) would
 * otherwise reach the components as a string and force every one of them to
 * defend against it. Doing it once, here, means a component can always expect
 * `message` to exist.
 */
function toApiError(error: HttpErrorResponse, url: string): ApiError {
  const body: unknown = error.error;

  if (isApiError(body)) {
    return body;
  }

  return {
    timestamp: new Date().toISOString(),
    status: error.status,
    error: error.statusText || 'Error',
    code: error.status === 0 ? 'NETWORK_ERROR' : 'UNEXPECTED_ERROR',
    message:
      error.status === 0
        ? 'The server is not reachable.'
        : 'The request could not be completed.',
    path: url,
  };
}

/** Structural check that a response body really is an `ApiError`. */
function isApiError(value: unknown): value is ApiError {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<ApiError>;
  return (
    typeof candidate.message === 'string' &&
    typeof candidate.code === 'string' &&
    typeof candidate.status === 'number'
  );
}
