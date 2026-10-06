import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './core/services/auth.service';
import { NotificationService } from './core/services/notification.service';
import { NotificationsComponent } from './shared/notifications.component';

/**
 * Application shell: the header, the notification area and the routed view.
 *
 * The header is only rendered for a signed-in user. That is presentation, not
 * protection: the API refuses unauthenticated calls regardless of what the
 * browser chooses to display.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, NotificationsComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);

  protected readonly auth = inject(AuthService);

  /** Drops the session and returns to the login page. */
  protected signOut(): void {
    this.auth.logout();
    this.notifications.clear();
    void this.router.navigate(['/login']);
  }
}
