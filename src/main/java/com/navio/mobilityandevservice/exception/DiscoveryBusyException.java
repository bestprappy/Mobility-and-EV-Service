package com.navio.mobilityandevservice.exception;
public class DiscoveryBusyException extends RuntimeException {
    public DiscoveryBusyException() { super("Place discovery is busy. Try again shortly."); }
}
