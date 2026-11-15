package com.navio.mobilityandevservice.exception;

/** The route needs a signed-in user and the gateway supplied no identity. */
public class AuthenticationRequiredException extends RuntimeException {
    public AuthenticationRequiredException(String message) { super(message); }
}
