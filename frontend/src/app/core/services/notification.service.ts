import { Injectable, signal } from '@angular/core';

/** Severity of a user-facing message. */
export type NotificationKind = 'success' | 'error' | 'info';

/** One message shown to the user. */
export interface Notification {
  id: number;
  kind: NotificationKind;
  message: string;
}

/**
 * Small in-memory message bus used to show success and error banners.
 *
 * Messages hold plain text and are rendered through Angular interpolation,
 * which escapes its input. Nothing here is ever inserted as HTML, so a message
 * built from an API response cannot become a script injection.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {

  private static readonly MAX_VISIBLE = 3;

  private nextId = 1;
  private readonly notificationsSignal = signal<Notification[]>([]);

  /** Read-only list of the messages currently on screen. */
  readonly notifications = this.notificationsSignal.asReadonly();

  success(message: string): void {
    this.push('success', message);
  }

  error(message: string): void {
    this.push('error', message);
  }

  info(message: string): void {
    this.push('info', message);
  }

  dismiss(id: number): void {
    this.notificationsSignal.update((list) => list.filter((n) => n.id !== id));
  }

  clear(): void {
    this.notificationsSignal.set([]);
  }

  private push(kind: NotificationKind, message: string): void {
    const notification: Notification = { id: this.nextId++, kind, message };

    this.notificationsSignal.update((list) => {
      const next = [...list, notification];
      // Keep the newest few only: a burst of failures must not push the page
      // content off the screen.
      return next.slice(-NotificationService.MAX_VISIBLE);
    });

    // Errors stay until dismissed (they usually need reading); the others
    // disappear on their own.
    if (kind !== 'error') {
      setTimeout(() => this.dismiss(notification.id), 5000);
    }
  }
}
