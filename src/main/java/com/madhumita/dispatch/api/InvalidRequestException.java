package com.madhumita.dispatch.api;

import java.util.Map;

/** Signals a client error (HTTP 400) together with field-level details that explain exactly what to fix. */
public class InvalidRequestException extends RuntimeException {
    private final transient Map<String, String> details;

    public InvalidRequestException(String message) { this(message, Map.of()); }

    public InvalidRequestException(String message, Map<String, String> details) {
        super(message);
        this.details = Map.copyOf(details);
    }

    public Map<String, String> getDetails() { return details; }
}
