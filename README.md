# Payment Service

Spring Boot service for creating payment records, creating provider orders, verifying client-side payment signatures, ingesting provider webhooks, and reconciling payment state.

This README reflects the code currently in the repository. Where the implementation is incomplete, that is called out explicitly instead of documenting an intended future flow as if it already exists.

## What It Does Today

- Exposes public payment APIs under `/payments`.
- Exposes internal APIs under `/internal/v1/payments`.
- Supports a single provider: `RAZORPAY`.
- Persists payment records, idempotency keys, and processed webhook events in PostgreSQL.
- Creates provider orders through Razorpay's `POST /v1/orders`.
- Verifies payment signatures and webhook signatures using HMAC SHA-256.
- Applies basic request authentication through servlet filters.

## What Is Not Fully Implemented Yet

- No seat-allocation or booking service integration is present.
- Payment initiation does not fetch authoritative pricing; it currently writes `amountMinor = 0` and `currency = INR`.
- A successful `/payments/{paymentId}/verify` call does not mark the payment as `SUCCESS`; it only stores `providerPaymentId` and keeps the payment `PENDING`.
- Webhook handling reports `bookingConfirmationTriggered=true` for success-like events, but no downstream booking confirmation call exists yet.
- Reconciliation contains placeholder refund and lock-release hooks only.
- The async end-to-end flow exists only partially through webhook status updates and reconcile handling.

## Runtime Shape

- Java: `21`
- Framework: Spring Boot `4.0.3`
- Database: PostgreSQL
- Build tool: Maven wrapper (`mvnw`, `mvnw.cmd`)

## Main Components

- `PaymentController`: public endpoints for initiate, status, cancel, verify.
- `InternalPaymentController`: internal webhook and reconcile endpoints.
- `PaymentService`: core public payment lifecycle logic.
- `InternalPaymentService`: webhook ingestion, duplicate detection, reconciliation.
- `PaymentGatewayRegistry`: provider lookup.
- `RazorpayPaymentGateway`: Razorpay-specific order creation and signature verification.
- `HttpRazorpayApiClient`: outbound Razorpay REST client.

## Authentication

Public endpoints under `/payments/*` require:

- `Authorization: Bearer <jwt>`

The JWT filter validates:

- HMAC SHA-256 signature
- issuer claim `iss`
- expiry claim `exp`

Internal endpoints under `/internal/*` require:

- `X-Internal-Auth: <shared secret>`

Exception:

- `/internal/v1/payments/webhook` is intentionally excluded from the internal-auth filter.
- The webhook instead requires a provider signature header.

## Configuration

Primary properties from [application.properties](/d:/Scaler-Projects/payment-service/src/main/resources/application.properties:1):

- `spring.datasource.url`
- `spring.datasource.username`
- `spring.datasource.password`
- `internal.api.shared-secret`
- `security.jwt.secret`
- `security.jwt.issuer` with default `user-service`
- `payment.razorpay.base-url`
- `payment.razorpay.key-id`
- `payment.razorpay.key-secret`
- `payment.razorpay.webhook-secret`

Useful environment variable overrides:

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `RAZORPAY_BASE_URL`
- `RAZORPAY_KEY_ID`
- `RAZORPAY_KEY_SECRET`
- `RAZORPAY_WEBHOOK_SECRET`

## Data Model

Core persisted entities:

- `payments`
- `payment_idempotency`
- `processed_webhooks`

The checked-in schema is in [scripts/db.sql](/d:/Scaler-Projects/payment-service/scripts/db.sql:1).

Important mismatch:

- The SQL script enforces `amount_minor > 0`.
- The current service implementation writes `amountMinor = 0` during payment initiation.

If you bootstrap strictly from `scripts/db.sql`, payment initiation will fail until pricing is integrated or the schema is adjusted.

## Payment Lifecycle In Code

1. `POST /payments/initiate`
   Creates a local payment in `CREATED`, creates a Razorpay order, updates local status to `PENDING`, and stores the idempotency record.
2. `POST /payments/{paymentId}/verify`
   Verifies the provider signature from the client callback payload and stores `providerPaymentId`.
3. `POST /internal/v1/payments/webhook`
   Verifies the webhook signature, resolves the payment, updates local payment status, and records the webhook as processed.
4. `POST /internal/v1/payments/{paymentId}/reconcile`
   Handles `SUCCESS` and `SUCCESS_CONFIRMATION_FAILED` payments. Success path is local-only. Failure path marks the payment `REFUNDED` and reports lock release, but both downstream actions are still placeholders.

## Payment Status Values

- `CREATED`
- `PENDING`
- `SUCCESS`
- `FAILED`
- `CANCELLED`
- `SUCCESS_CONFIRMATION_FAILED`
- `REFUNDED`

## Running Locally

1. Start PostgreSQL and create the target database.
2. Configure DB and Razorpay credentials through properties or environment variables.
3. Run:

```powershell
.\mvnw.cmd spring-boot:run
```

Or run tests:

```powershell
.\mvnw.cmd test
```

## API Reference

Endpoint details and example payloads are in [API.md](/d:/Scaler-Projects/payment-service/API.md:1).
