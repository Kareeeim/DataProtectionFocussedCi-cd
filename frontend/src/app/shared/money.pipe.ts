import { Pipe, PipeTransform } from '@angular/core';

/**
 * Renders an exact decimal string as a localised currency amount.
 *
 * The API sends money as a string so that no value is ever silently rounded by
 * a floating-point conversion (see `api.models.ts`). This pipe is the single
 * place where that string is turned into something to look at, and it keeps the
 * conversion honest:
 *
 * - Amounts within `Number.MAX_SAFE_INTEGER` (about 9 quadrillion) are formatted
 *   with `Intl.NumberFormat`, which gives correct locale-aware grouping,
 *   separators and currency placement.
 * - Anything larger is printed as the raw decimal string followed by the
 *   currency code. Above that threshold a `number` can no longer represent every
 *   integer, so formatting through one could display a value that differs from
 *   the one stored. Showing the exact string is uglier and correct, which is the
 *   right way round for money.
 *
 * Usage: `{{ account.balance | money: account.currency }}`
 */
@Pipe({
  name: 'money',
  standalone: true,
})
export class MoneyPipe implements PipeTransform {

  /** Above this, a JS number cannot represent every integer exactly. */
  private static readonly SAFE_LIMIT = Number.MAX_SAFE_INTEGER;

  transform(value: string | null | undefined, currency = 'EUR', locale = 'en-GB'): string {
    if (value === null || value === undefined || value === '') {
      return '';
    }

    const asNumber = Number(value);

    if (!Number.isFinite(asNumber) || Math.abs(asNumber) > MoneyPipe.SAFE_LIMIT) {
      // Too large to format safely: show exactly what the server sent.
      return `${value} ${currency}`;
    }

    try {
      return new Intl.NumberFormat(locale, {
        style: 'currency',
        currency,
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      }).format(asNumber);
    } catch {
      // Unknown/invalid currency code: fall back instead of breaking the view.
      return `${value} ${currency}`;
    }
  }
}
