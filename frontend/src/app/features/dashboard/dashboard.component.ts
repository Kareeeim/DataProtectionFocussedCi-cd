import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Account, ApiError } from '../../core/models/api.models';
import { AccountService } from '../../core/services/account.service';
import { AuthService } from '../../core/services/auth.service';
import { MoneyPipe } from '../../shared/money.pipe';

/** Total held in one currency. */
interface CurrencyTotal {
  currency: string;
  total: string;
}

/**
 * Dashboard: the signed-in customer's accounts and their balances.
 *
 * The view has four distinct states, and models all of them explicitly:
 * loading, failed, empty and populated. Each is rendered separately, so the
 * user is never left looking at a blank page wondering whether something is
 * still happening - and a failure never wipes out the rest of the interface.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, MoneyPipe],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit {

  private readonly accountService = inject(AccountService);

  protected readonly auth = inject(AuthService);

  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly accounts = signal<Account[]>([]);

  /** True once a successful response has arrived, whatever it contained. */
  protected readonly loaded = signal(false);

  /**
   * Total per currency.
   *
   * The sum is computed on the string amounts returned by the API. It is
   * deliberately computed by scaling the two-decimal strings to integers
   * (cents) rather than by adding floating-point numbers: `0.1 + 0.2` is
   * `0.30000000000000004` in JavaScript, and a dashboard that misstates a total
   * by a fraction of a cent is a dashboard nobody should trust.
   */
  protected readonly totals = computed<CurrencyTotal[]>(() => {
    const centsPerCurrency = new Map<string, bigint>();

    for (const account of this.accounts()) {
      const cents = toCents(account.balance);
      const current = centsPerCurrency.get(account.currency) ?? 0n;
      centsPerCurrency.set(account.currency, current + cents);
    }

    return [...centsPerCurrency.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([currency, cents]) => ({ currency, total: fromCents(cents) }));
  });

  ngOnInit(): void {
    this.loadAccounts();
  }

  /** (Re)loads the accounts and drives the loading/error/empty states. */
  protected loadAccounts(): void {
    this.loading.set(true);
    this.errorMessage.set(null);

    this.accountService.listAccounts().subscribe({
      next: (accounts) => {
        this.accounts.set(accounts);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: (error: ApiError) => {
        // The previous list is intentionally kept: a failed refresh must not
        // throw away data the user is looking at.
        this.errorMessage.set(error.message);
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  /** Last four digits, enough for the user to recognise the account. */
  protected shortNumber(accountNumber: string): string {
    return accountNumber.length <= 4 ? accountNumber : `••••${accountNumber.slice(-4)}`;
  }
}

/** Parses an exact decimal string into a whole number of cents. */
function toCents(amount: string): bigint {
  const negative = amount.startsWith('-');
  const [whole = '0', fraction = ''] = amount.replace('-', '').split('.');
  const paddedFraction = (fraction + '00').slice(0, 2);
  const cents = BigInt(whole) * 100n + BigInt(paddedFraction);
  return negative ? -cents : cents;
}

/** Renders a whole number of cents back as an exact decimal string. */
function fromCents(cents: bigint): string {
  const negative = cents < 0n;
  const absolute = negative ? -cents : cents;
  const whole = absolute / 100n;
  const fraction = (absolute % 100n).toString().padStart(2, '0');
  return `${negative ? '-' : ''}${whole}.${fraction}`;
}
