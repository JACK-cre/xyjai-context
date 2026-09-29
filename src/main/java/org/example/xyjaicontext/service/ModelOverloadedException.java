package org.example.xyjaicontext.service;

/** The bounded model executor rejected the task; retrying immediately worsens overload. */
public class ModelOverloadedException extends ModelCallException {

    public ModelOverloadedException(String message, Throwable cause) {
        super(message, cause);
    }
}
