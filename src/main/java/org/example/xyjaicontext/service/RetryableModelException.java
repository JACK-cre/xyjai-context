package org.example.xyjaicontext.service;

/** A model failure that may succeed when retried within the request budget. */
public class RetryableModelException extends ModelCallException {

    public RetryableModelException(String message) {
        super(message);
    }

    public RetryableModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
