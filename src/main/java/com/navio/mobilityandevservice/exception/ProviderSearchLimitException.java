package com.navio.mobilityandevservice.exception;

/** A provider-search allowance is used up. Nothing was sent to Google and nothing was charged. */
public class ProviderSearchLimitException extends RuntimeException {
    public enum Scope { USER_DAY, DAY, MONTH }

    private final Scope scope;

    public ProviderSearchLimitException(Scope scope) {
        super("Provider search limit reached: " + scope);
        this.scope = scope;
    }

    public ProviderSearchLimitException(Scope scope, Throwable cause) {
        super("Provider search limit reached: " + scope, cause);
        this.scope = scope;
    }

    public Scope scope() { return scope; }
}
