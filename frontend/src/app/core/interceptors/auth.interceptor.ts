import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../services/auth.service';

/**
 * Attaches the access token to outgoing API calls.
 *
 * Two scoping rules, both deliberate:
 *
 * 1. The header is added **only for same-origin `/api` requests**. A token is a
 *    bearer credential: anybody who receives it can use it. Forwarding it to
 *    every host the page happens to call - an analytics endpoint, a CDN, a
 *    mistyped URL - would leak the session to a third party.
 *
 * 2. The request is cloned rather than mutated. `HttpRequest` instances are
 *    immutable, which is what stops one interceptor from silently changing what
 *    another one sees.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);

  if (!request.url.startsWith('/api')) {
    return next(request);
  }

  const token = auth.token();
  if (!token) {
    return next(request);
  }

  return next(
    request.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
    }),
  );
};
