package com.navio.mobilityandevservice.exception;

public class ProviderClientException extends RuntimeException {

    public ProviderClientException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProviderClientException(String message) {
        super(message);
    }
}
