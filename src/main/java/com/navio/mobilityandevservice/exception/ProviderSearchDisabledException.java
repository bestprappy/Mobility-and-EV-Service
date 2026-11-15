package com.navio.mobilityandevservice.exception;

/** Explicit Google search is switched off with {@code navio.places.provider-search.enabled}; no provider call is made. */
public class ProviderSearchDisabledException extends RuntimeException {
    public ProviderSearchDisabledException() { super("Search beyond Navio's catalogue is turned off."); }
}
