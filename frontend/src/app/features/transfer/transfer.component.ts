import { Component, OnInit, computed, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Account, ApiError, TransferResponse } from '../../core/models/api.models';
import { AccountService } from '../../core/services/account.service';
import { NotificationService } from '../../core/services/notification.service';
import { TransferService } from '../../core/services/transfer.service';
import { MoneyPipe } from '../../shared/money.pipe';

/** Which of the three steps of the wizard is on screen. */
type Step = 'form' | 'confirm' | 'result';

/**
 * Transfer form: choose, review, confirm.
 *
 * The confirmation step is not decoration. Moving money between accounts is
 * irreversible in this application - there is no undo - so the user gets one
 * explicit screen showing exactly what is about to happen, with the source, the
 * destination and the amount, before anything is sent.
 *
 * All validation here is duplicated on purpose. The checks in this file exist to
 * give fast, specific feedback; the checks in the API are the ones that
 * actually protect the data, and the component treats a 400/404/422 from the
 * server as authoritative.
 */
@Component({
  selector: 'app-transfer',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, MoneyPipe],
  templateUrl: './transfer.component.html',
  styleUrl: './transfer.component.scss',
})
export class TransferComponent implements OnInit {

  private readonly formBuilder = inject(FormBuilder);
  private readonly accountService = inject(AccountService);
  private readonly transferService = inject(TransferService);
  private readonly notifications = inject(NotificationService);

  protected readonly step = signal<Step>('form');
  protected readonly accounts = signal<Account[]>([]);
  protected readonly loadingAccounts = signal(true);
  protected readonly accountsError = signal<string | null>(null);

  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);
  protected readonly fieldErrors = signal<Record<string, string>>({});

  /** Populated only on success; drives the receipt panel. */
  protected readonly receipt = signal<TransferResponse | null>(null);

  /**
   * Amount pattern: up to 17 integer digits and at most two decimals.
   * Mirrors the server's `@Digits(integer = 17, fraction = 2)`, and rejects
   * anything that is not a plain positive decimal - no sign, no exponent, no
   * thousands separators.
   */
  private static readonly AMOUNT_PATTERN = /^\d{1,17}(\.\d{1,2})?$/;

  protected readonly form = this.formBuilder.nonNullable.group(
    {
      sourceAccountId: [0, [Validators.required, Validators.min(1)]],
      destinationAccountId: [0, [Validators.required, Validators.min(1)]],
      amount: [
        '',
        [
          Validators.required,
          Validators.pattern(TransferComponent.AMOUNT_PATTERN),
        ],
      ],
      description: ['', [Validators.maxLength(140)]],
    },
    {
      validators: [
        distinctAccountsValidator,
        sufficientFundsValidator(() => this.accounts()),
      ],
    },
  );

  protected readonly sourceAccount = computed(() =>
    this.accounts().find((a) => a.id === Number(this.form.controls.sourceAccountId.value)) ?? null,
  );

  protected readonly destinationAccount = computed(() =>
    this.accounts().find(
      (a) => a.id === Number(this.form.controls.destinationAccountId.value),
    ) ?? null,
  );

  ngOnInit(): void {
    this.loadAccounts();
  }

  /** Moves from the form to the review screen. */
  protected review(): void {
    this.submitError.set(null);

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.step.set('confirm');
  }

  /** Goes back to editing without losing what was typed. */
  protected edit(): void {
    this.submitError.set(null);
    this.step.set('form');
  }

  /**
   * Sends the transfer.
   *
   * The amount is sent as the exact string the user typed: parsing it into a
   * JavaScript number here would introduce the floating-point rounding the API
   * contract exists to avoid.
   */
  protected confirm(): void {
    if (this.form.invalid) {
      this.step.set('form');
      return;
    }

    const values = this.form.getRawValue();
    const description = values.description.trim();

    this.submitting.set(true);
    this.submitError.set(null);
    this.fieldErrors.set({});

    this.transferService
      .createTransfer({
        sourceAccountId: Number(values.sourceAccountId),
        destinationAccountId: Number(values.destinationAccountId),
        amount: values.amount,
        description: description === '' ? undefined : description,
      })
      .subscribe({
        next: (response) => {
          this.submitting.set(false);
          this.receipt.set(response);
          this.step.set('result');
          this.notifications.success('Transfer completed.');
        },
        error: (error: ApiError) => {
          this.submitting.set(false);

          const violations: Record<string, string> = {};
          for (const violation of error.fieldErrors ?? []) {
            violations[violation.field] = violation.message;
          }
          this.fieldErrors.set(violations);
          this.submitError.set(error.message);

          // A rejected payload is corrected on the form, so send the user back
          // there with their input still intact. A refusal that is not about
          // the input (insufficient funds) is shown on the review screen,
          // where the decision to retry is made.
          if (error.status === 400 && Object.keys(violations).length > 0) {
            this.step.set('form');
          }
        },
      });
  }

  /** Resets the wizard so a second transfer can be entered. */
  protected startOver(): void {
    this.receipt.set(null);
    this.submitError.set(null);
    this.fieldErrors.set({});
    this.form.reset({
      sourceAccountId: 0,
      destinationAccountId: 0,
      amount: '',
      description: '',
    });
    this.step.set('form');
    // Balances changed, so the choices and the funds check are refreshed.
    this.loadAccounts();
  }

  protected showError(controlName: 'amount' | 'description'): boolean {
    const control = this.form.controls[controlName];
    return control.invalid && (control.dirty || control.touched);
  }

  protected shortNumber(accountNumber: string): string {
    return accountNumber.length <= 4 ? accountNumber : `••••${accountNumber.slice(-4)}`;
  }

  private loadAccounts(): void {
    this.loadingAccounts.set(true);
    this.accountsError.set(null);

    this.accountService.listAccounts().subscribe({
      next: (accounts) => {
        this.accounts.set(accounts);
        this.loadingAccounts.set(false);
        // Re-evaluate the funds check now that the balances are known.
        this.form.updateValueAndValidity({ emitEvent: false });
      },
      error: (error: ApiError) => {
        this.accountsError.set(error.message);
        this.loadingAccounts.set(false);
      },
    });
  }
}

/**
 * Rejects a transfer whose source and destination are the same account.
 *
 * The API refuses it too (`SAME_ACCOUNT`, and a database CHECK constraint
 * behind that). Catching it here simply means the user is told before pressing
 * the button rather than after.
 */
const distinctAccountsValidator: ValidatorFn = (
  group: AbstractControl,
): ValidationErrors | null => {
  const source = group.get('sourceAccountId')?.value;
  const destination = group.get('destinationAccountId')?.value;

  if (source && destination && Number(source) === Number(destination)) {
    return { sameAccount: true };
  }
  return null;
};

/**
 * Refuses an amount larger than the source account balance.
 *
 * The comparison is done on the exact decimal strings, scaled to whole cents,
 * so it cannot be fooled by floating-point rounding - the same reason the API
 * sends money as a string. The server checks the balance again, against the
 * locked row, and its answer wins: this validator reads a balance that may be
 * seconds old.
 */
function sufficientFundsValidator(
  getAccounts: () => Account[],
): ValidatorFn {
  return (group: AbstractControl): ValidationErrors | null => {
    const sourceId = Number(group.get('sourceAccountId')?.value);
    const amount = group.get('amount')?.value as string;

    if (!sourceId || !amount || !/^\d{1,17}(\.\d{1,2})?$/.test(amount)) {
      return null;
    }

    const account = getAccounts().find((a) => a.id === sourceId);
    if (!account) {
      return null;
    }

    if (toCents(amount) > toCents(account.balance)) {
      return { insufficientFunds: true };
    }
    return null;
  };
}

/** Parses an exact decimal string into a whole number of cents. */
function toCents(amount: string): bigint {
  const [whole = '0', fraction = ''] = amount.split('.');
  return BigInt(whole) * 100n + BigInt((fraction + '00').slice(0, 2));
}
