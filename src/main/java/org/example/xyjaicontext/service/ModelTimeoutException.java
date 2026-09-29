package org.example.xyjaicontext.service;

public class ModelTimeoutException extends RetryableModelException {

    public ModelTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
