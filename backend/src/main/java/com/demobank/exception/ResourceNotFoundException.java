package com.demobank.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a resource does not exist, <em>or exists but belongs to another
 * customer</em>.
 *
 * <p>The two situations are merged on purpose. Answering {@code 403 Forbidden}
 * for somebody else's account would confirm that the account id is real, which
 * turns the endpoint into an enumeration oracle: an attacker walks the id space,
 * notes which values return 403 and learns exactly how many accounts the bank
 * holds and which ones are worth attacking. Returning {@code 404} for both
 * leaks nothing.</p>
 *
 * <p>The wording of the message is likewise neutral: "Account not found",
 * never "This account belongs to another user".</p>
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }
}
