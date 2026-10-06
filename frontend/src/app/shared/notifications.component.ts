import { Component, inject } from '@angular/core';
import { NotificationService } from '../core/services/notification.service';

/**
 * Renders the transient success/error messages produced by
 * `NotificationService`.
 *
 * Each message is bound with `{{ }}`, so Angular escapes it. A message derived
 * from an API response can therefore never be interpreted as markup.
 *
 * `aria-live="polite"` makes screen readers announce new messages without
 * interrupting whatever the user is doing.
 */
@Component({
  selector: 'app-notifications',
  standalone: true,
  template: `
    <div class="notifications" aria-live="polite" aria-atomic="false">
      @for (notification of notifications.notifications(); track notification.id) {
        <div class="notification" [class]="'notification--' + notification.kind" role="status">
          <span class="notification__text">{{ notification.message }}</span>
          <button
            type="button"
            class="notification__close"
            [attr.aria-label]="'Dismiss message'"
            (click)="notifications.dismiss(notification.id)"
          >
            &times;
          </button>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .notifications {
        position: fixed;
        top: 1rem;
        right: 1rem;
        z-index: 1000;
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        max-width: min(24rem, calc(100vw - 2rem));
      }

      .notification {
        display: flex;
        align-items: flex-start;
        gap: 0.75rem;
        padding: 0.75rem 1rem;
        border-radius: var(--radius-md);
        border-left: 4px solid var(--colour-border-strong);
        background: var(--colour-surface);
        box-shadow: var(--shadow-lg);
        font-size: 0.9rem;
        animation: slide-in 0.18s ease-out;
      }

      .notification--success {
        border-left-color: var(--colour-success);
      }
      .notification--error {
        border-left-color: var(--colour-danger);
      }
      .notification--info {
        border-left-color: var(--colour-info);
      }

      .notification__text {
        flex: 1;
        line-height: 1.4;
        word-break: break-word;
      }

      .notification__close {
        border: none;
        background: transparent;
        cursor: pointer;
        font-size: 1.25rem;
        line-height: 1;
        color: var(--colour-text-muted);
        padding: 0 0.25rem;
      }

      .notification__close:hover {
        color: var(--colour-text);
      }

      @keyframes slide-in {
        from {
          opacity: 0;
          transform: translateX(1rem);
        }
        to {
          opacity: 1;
          transform: translateX(0);
        }
      }

      @media (prefers-reduced-motion: reduce) {
        .notification {
          animation: none;
        }
      }
    `,
  ],
})
export class NotificationsComponent {
  protected readonly notifications = inject(NotificationService);
}
