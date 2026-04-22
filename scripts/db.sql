BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

SET search_path TO payment_db;

CREATE TABLE IF NOT EXISTS payments (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id              UUID NOT NULL,
  event_id             UUID NOT NULL,
  lock_id              UUID NOT NULL,
  amount_minor         BIGINT NOT NULL CHECK (amount_minor > 0),
  currency             VARCHAR(3) NOT NULL,
  provider             VARCHAR(30) NOT NULL,
  status               VARCHAR(40) NOT NULL,
  provider_order_id    VARCHAR(128),
  provider_payment_id  VARCHAR(128),
  failure_reason       VARCHAR(512),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  CONSTRAINT chk_payment_status
    CHECK (status IN (
      'CREATED',
      'PENDING',
      'SUCCESS',
      'FAILED',
      'SUCCESS_CONFIRMATION_FAILED',
      'REFUNDED',
      'CANCELLED'
    )),

  CONSTRAINT chk_currency_len CHECK (length(currency) = 3)
);

CREATE INDEX IF NOT EXISTS idx_payments_user_created
  ON payments(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_payments_event
  ON payments(event_id);

CREATE INDEX IF NOT EXISTS idx_payments_lock
  ON payments(lock_id);

CREATE INDEX IF NOT EXISTS idx_payments_status
  ON payments(status);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_provider_order
  ON payments(provider, provider_order_id)
  WHERE provider_order_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_provider_payment
  ON payments(provider, provider_payment_id)
  WHERE provider_payment_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS payment_idempotency (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL,
  idempotency_key   VARCHAR(128) NOT NULL,
  request_hash      VARCHAR(128) NOT NULL,
  payment_id        UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS processed_webhooks (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider           VARCHAR(30) NOT NULL,
  provider_event_id  VARCHAR(128) NOT NULL,
  processed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (provider, provider_event_id)
);

COMMIT;
