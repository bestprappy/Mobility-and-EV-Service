package com.navio.mobilityandevservice.exception;

/** The caller is signed in but does not hold an administrator role. */
public class AdminAccessDeniedException extends RuntimeException {
    public AdminAccessDeniedException() {
        super("Administrator role required");
    }
}
