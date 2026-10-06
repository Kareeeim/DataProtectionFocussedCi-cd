import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Fallback page for an unknown route. */
@Component({
  selector: 'app-not-found',
  standalone: true,
  imports: [RouterLink],
  template: `
    <div class="card state">
      <p class="state__title">Page not found</p>
      <p>The page you asked for does not exist.</p>
      <a class="button button--secondary button--sm" routerLink="/dashboard">
        Back to dashboard
      </a>
    </div>
  `,
})
export class NotFoundComponent {}
