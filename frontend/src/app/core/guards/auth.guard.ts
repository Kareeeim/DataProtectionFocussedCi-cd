import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * Blocks navigation to protected routes when there is no session.
 *
 * This guard is a **usability** feature, not a security control. Everything it
 * prevents - reaching the dashboard without a token - the API refuses anyway,
 * with a 401 that the error interceptor turns into the same redirect. Client-side
 * guards can be bypassed by anyone who cares to open the developer console; the
 * server-side ownership checks cannot. Treating the guard as the protection
 * would be the classic mistake of enforcing authorisation in the browser.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.hasToken()) {
    return true;
  }

  // Remember where the user wanted to go, so the login page can send them
  // there afterwards instead of dumping them on the dashboard.
  return router.createUrlTree(['/login'], {
    queryParams: { returnUrl: state.url },
  });
};
