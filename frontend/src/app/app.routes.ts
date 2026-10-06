import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

/**
 * Application routes.
 *
 * Everything except the login page sits behind `authGuard`. The guard is a
 * usability measure - it avoids flashing an empty dashboard at a signed-out
 * user - while the real enforcement happens on the server, which refuses every
 * call that does not carry a valid token.
 *
 * Every feature is lazily loaded, so the login page ships without the code for
 * the statement table or the transfer form.
 */
export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in - DemoBank',
    loadComponent: () => import('./features/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'dashboard',
    title: 'Dashboard - DemoBank',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },
  {
    path: 'accounts/:id',
    title: 'Account - DemoBank',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/account-detail/account-detail.component').then(
        (m) => m.AccountDetailComponent,
      ),
  },
  {
    path: 'transfer',
    title: 'New transfer - DemoBank',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/transfer/transfer.component').then((m) => m.TransferComponent),
  },
  // Default landing: the guard sends an anonymous visitor on to the login page.
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: '**',
    title: 'Not found - DemoBank',
    loadComponent: () =>
      import('./features/not-found/not-found.component').then((m) => m.NotFoundComponent),
  },
];
