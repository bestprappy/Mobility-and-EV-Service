package com.navio.mobilityandevservice.exception;

import com.navio.mobilityandevservice.provider.usage.GoogleUsageMeter;

/** The month's Google free tier for this meter is used up (or unverifiable), so no call was made. */
public class FreeTierExhaustedException extends ProviderClientException {

    private final GoogleUsageMeter meter;

    public FreeTierExhaustedException(GoogleUsageMeter meter) {
        super("Google " + meter + " free tier is used up for this month");
        this.meter = meter;
    }

    public FreeTierExhaustedException(GoogleUsageMeter meter, Throwable cause) {
        super("Google " + meter + " usage could not be checked", cause);
        this.meter = meter;
    }

    public GoogleUsageMeter meter() {
        return meter;
    }
}
