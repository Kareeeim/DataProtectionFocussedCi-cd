import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { TransferRequest, TransferResponse } from '../models/api.models';

/**
 * Creates simulated transfers.
 */
@Injectable({ providedIn: 'root' })
export class TransferService {

  private readonly http = inject(HttpClient);

  /**
   * Executes a transfer.
   *
   * `amount` is sent as a **string**, matching the API contract. Sending it as
   * a JSON number would hand the value to the browser's floating-point parser
   * before it ever leaves the client; the server would then receive whatever
   * the double happened to round to. Keeping it a string means the exact
   * decimal the user typed is the exact decimal that arrives.
   *
   * The request carries no user identifier: the server takes the customer from
   * the access token, so the client cannot influence whose accounts are
   * debited.
   */
  createTransfer(request: TransferRequest): Observable<TransferResponse> {
    return this.http.post<TransferResponse>('/api/transfers', request);
  }
}
