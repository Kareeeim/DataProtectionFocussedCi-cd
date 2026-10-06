import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Account, ApiError, Movement } from '../../core/models/api.models';
import { AccountService } from '../../core/services/account.service';
import { MoneyPipe } from '../../shared/money.pipe';

/**
 * Account detail: balance, filters and the statement.
 *
 * The date filters are evaluated by the server, not in the browser. Filtering
 * an already-loaded page of movements would quietly give the wrong answer as
 * soon as an account has more movements than fit in one response, so the
 * component sends the range to the API and displays what comes back.
 */
@Component({
  selector: 'app-account-detail',
  standalone: true,
  imports: [RouterLink, MoneyPipe],
  templateUrl: './account-detail.component.html',
  styleUrl: './account-detail.component.scss',
})
export class AccountDetailComponent {

  private readonly accountService = inject(AccountService);
  private readonly router = inject(Router);

  /**
   * Account id from the route, bound by `withComponentInputBinding()`.
   * Declared as an input rather than read from `ActivatedRoute` so that
   * navigating between two accounts re-runs the loader automatically.
   */
  readonly id = input<string>('');

  protected readonly account = signal<Account | null>(null);
  protected readonly movements = signal<Movement[]>([]);
  protected readonly otherAccounts = signal<Account[]>([]);

  protected readonly loadingAccount = signal(true);
  protected readonly loadingMovements = signal(true);
  protected readonly accountError = signal<string | null>(null);
  protected readonly movementsError = signal<string | null>(null);
  protected readonly filtersApplied = signal(false);

  protected readonly fromDate = signal('');
  protected readonly toDate = signal('');
  protected readonly filterError = signal<string | null>(null);

  /** True when a search returned nothing *because* a filter was applied. */
  protected readonly emptyDueToFilters = computed(
    () => this.movements().length === 0 && this.filtersApplied() && !this.movementsError(),
  );

  constructor() {
    // Reloads whenever the route parameter changes, which is what makes the
    // account switcher below work without any extra wiring.
    effect(() => {
      const accountId = Number(this.id());
      if (!Number.isInteger(accountId) || accountId <= 0) {
        this.accountError.set('This account address is not valid.');
        this.loadingAccount.set(false);
        this.loadingMovements.set(false);
        return;
      }
      // A new account means the previous filters no longer apply.
      this.fromDate.set('');
      this.toDate.set('');
      this.filterError.set(null);
      this.filtersApplied.set(false);

      this.loadAccount(accountId);
      this.loadMovements(accountId);
      this.loadAccountChoices();
    });
  }

  /** Re-runs the statement query with the dates currently selected. */
  protected applyFilters(): void {
    const from = this.fromDate();
    const to = this.toDate();

    // Checked here for immediate feedback; the API validates it again and
    // answers 400 with a machine-readable code.
    if (from && to && from > to) {
      this.filterError.set('The start date must not be after the end date.');
      return;
    }

    this.filterError.set(null);
    this.loadMovements(Number(this.id()), from || undefined, to || undefined);
  }

  /** Clears the filters and reloads the full statement. */
  protected clearFilters(): void {
    this.fromDate.set('');
    this.toDate.set('');
    this.filterError.set(null);
    this.loadMovements(Number(this.id()));
  }

  /** Switches to another account of the same customer. */
  protected onAccountChange(event: Event): void {
    const selected = (event.target as HTMLSelectElement).value;
    void this.router.navigate(['/accounts', selected]);
  }

  /** Formats an ISO instant for display in the user's locale. */
  protected formatDate(isoInstant: string): string {
    const parsed = new Date(isoInstant);
    if (Number.isNaN(parsed.getTime())) {
      return isoInstant;
    }
    return new Intl.DateTimeFormat('en-GB', {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(parsed);
  }

  /** Shortens an account number for display. */
  protected shortNumber(accountNumber: string): string {
    return accountNumber.length <= 4 ? accountNumber : `••••${accountNumber.slice(-4)}`;
  }

  protected trackMovement(_index: number, movement: Movement): number {
    return movement.id;
  }

  private loadAccount(accountId: number): void {
    this.loadingAccount.set(true);
    this.accountError.set(null);

    this.accountService.getAccount(accountId).subscribe({
      next: (account) => {
        this.account.set(account);
        this.loadingAccount.set(false);
      },
      error: (error: ApiError) => {
        this.account.set(null);
        this.accountError.set(error.message);
        this.loadingAccount.set(false);
      },
    });
  }

  private loadMovements(accountId: number, from?: string, to?: string): void {
    this.loadingMovements.set(true);
    this.movementsError.set(null);

    this.accountService.listMovements(accountId, from, to).subscribe({
      next: (movements) => {
        this.movements.set(movements);
        this.loadingMovements.set(false);
        this.filtersApplied.set(Boolean(from || to));
      },
      error: (error: ApiError) => {
        // The old rows stay on screen: a failed filter must not destroy the
        // statement the user was reading.
        this.movementsError.set(error.message);
        this.loadingMovements.set(false);
      },
    });
  }

  /**
   * Loads the customer's accounts so the header can offer a switcher.
   * Only the current customer's accounts are ever returned by the API, so the
   * list cannot be used to reach somebody else's data.
   */
  private loadAccountChoices(): void {
    this.accountService.listAccounts().subscribe({
      next: (accounts) => this.otherAccounts.set(accounts),
      error: () => this.otherAccounts.set([]),
    });
  }
}
