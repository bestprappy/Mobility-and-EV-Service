-- Google Maps Platform calls counted per billing month so the service stops at the free tier.
-- usage_month is the first day of the month in Google's billing time zone (America/Los_Angeles).
CREATE TABLE IF NOT EXISTS ev.provider_monthly_usage (
    usage_month   DATE        NOT NULL,
    meter         VARCHAR(40) NOT NULL,
    request_count INTEGER     NOT NULL DEFAULT 0 CHECK (request_count >= 0),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (usage_month, meter)
);

COMMENT ON TABLE ev.provider_monthly_usage IS 'Google Maps Platform requests per billing month, capped below the free tier';
