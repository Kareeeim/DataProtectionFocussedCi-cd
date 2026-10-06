import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { errorInterceptor } from './core/interceptors/error.interceptor';

/**
 * Application-wide providers.
 *
 * `withInterceptors` installs the two cross-cutting HTTP concerns in order:
 * the auth interceptor adds the bearer token, the error interceptor converts any
 * failure into the API's error contract and decides whether the session has to
 * be dropped. Both are functional interceptors, which makes their dependencies
 * explicit (`inject()`) instead of constructor-injected through a class.
 *
 * `withComponentInputBinding()` lets route parameters be received as component
 * inputs, so the account detail view reads its `id` as a signal input rather
 * than by subscribing to `ActivatedRoute` by hand.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([authInterceptor, errorInterceptor])),
  ],
};
