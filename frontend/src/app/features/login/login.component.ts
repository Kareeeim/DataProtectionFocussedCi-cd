import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiError } from '../../core/models/api.models';
import { AuthService } from '../../core/services/auth.service';
import { NotificationService } from '../../core/services/notification.service';

/**
 * Sign-in page.
 *
 * The form is built with `ReactiveForms` and validated on the client for
 * immediate feedback - so the user is told about a malformed email before a
 * round trip - but the same rules are enforced again by the API. Client-side
 * validation is a convenience; it is never the control that keeps bad input
 * out.
 *
 * The component never stores the password anywhere beyond the form control, and
 * clears the form as soon as the request completes.
 */
@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {

  private readonly formBuilder = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly notifications = inject(NotificationService);

  /** Blocks the form and shows a spinner while the request is in flight. */
  protected readonly submitting = signal(false);

  /** Message from the API, shown above the form. */
  protected readonly errorMessage = signal<string | null>(null);

  /** Per-field messages returned by the API, keyed by field name. */
  protected readonly serverFieldErrors = signal<Record<string, string>>({});

  protected readonly form = this.formBuilder.nonNullable.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    password: [
      '',
      [Validators.required, Validators.minLength(8), Validators.maxLength(72)],
    ],
  });

  /** Fills the form with the documented demo account, for convenience. */
  protected useDemoCredentials(): void {
    this.form.setValue({ email: 'alice@demo.local', password: 'Demo1234!' });
    this.errorMessage.set(null);
    this.serverFieldErrors.set({});
  }

  protected submit(): void {
    this.errorMessage.set(null);
    this.serverFieldErrors.set({});

    if (this.form.invalid) {
      // Touching every control makes the existing errors visible; the form
      // itself is not submitted.
      this.form.markAllAsTouched();
      return;
    }

    const { email, password } = this.form.getRawValue();
    this.submitting.set(true);

    this.auth.login(email, password).subscribe({
      next: (response) => {
        this.submitting.set(false);
        // The password is no longer needed once the token exists.
        this.form.controls.password.reset();
        this.notifications.success(`Welcome back, ${response.user.fullName}.`);
        void this.router.navigateByUrl(this.resolveReturnUrl());
      },
      error: (error: ApiError) => {
        this.submitting.set(false);
        this.errorMessage.set(error.message);

        // Field-level detail from the API is attached to the matching control,
        // so a rejected email is highlighted where the user can see it.
        const fieldErrors: Record<string, string> = {};
        for (const violation of error.fieldErrors ?? []) {
          fieldErrors[violation.field] = violation.message;
        }
        this.serverFieldErrors.set(fieldErrors);
      },
    });
  }

  /** True when the control is invalid *and* the user has already interacted. */
  protected showError(controlName: 'email' | 'password'): boolean {
    const control = this.form.controls[controlName];
    return control.invalid && (control.dirty || control.touched);
  }

  /**
   * Resolves where to go after signing in.
   *
   * Only paths that start with a single `/` are accepted, so a crafted
   * `returnUrl` cannot turn the login page into an open redirect that sends the
   * user to another site.
   */
  private resolveReturnUrl(): string {
    const requested = this.route.snapshot.queryParamMap.get('returnUrl');
    if (requested && requested.startsWith('/') && !requested.startsWith('//')) {
      return requested;
    }
    return '/dashboard';
  }
}
