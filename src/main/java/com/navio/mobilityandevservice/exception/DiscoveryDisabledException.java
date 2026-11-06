package com.navio.mobilityandevservice.exception;

/** Places discovery is switched off with {@code navio.discovery.enabled}; no provider call is made. */
public class DiscoveryDisabledException extends RuntimeException {
    public DiscoveryDisabledException() { super("Place discovery is turned off."); }
}
