-- Billing months in which an administrator let Google calls continue past Navio's limits,
-- for example while free trial credit covers the overage. Calls are still counted.
-- A row covers one month only, so limits come back on their own when the month resets.
CREATE TABLE IF NOT EXISTS ev.provider_limit_override (
    usage_month DATE        PRIMARY KEY,
    granted_by  UUID,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE ev.provider_limit_override IS 'Months whose Google free-tier limits an administrator chose to ignore';
