package org.example.xyjaicontext.service;

/** Raised when the configured model cannot produce a usable response. */
public class ModelCallException extends RuntimeException {

    public ModelCallException(String message) {
        super(message);
    }

    public ModelCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
