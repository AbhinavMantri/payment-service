# API Reference

This file describes the API contract implemented by the current codebase.

Base paths:

- Public API: `/payments`
- Internal API: `/internal/v1/payments`

Response envelope:

```json
{
  "status": "SUCCESS",
  "message": "Human readable message"
}
```

`status` is always one of:

- `SUCCESS`
- `FAILURE`

## Auth

### Public endpoints

All `/payments/*` routes require:

```http
Authorization: Bearer <jwt>
```

If the header is missing or malformed:

```json
{
  "status": "FAILURE",
  "message": "Missing or invalid Authorization header"
}
```

If the token fails validation:

```json
{
  "status": "FAILURE",
  "message": "Invalid JWT token"
}
```

### Internal endpoints

All `/internal/*` routes require:

```http
X-Internal-Auth: <shared-secret>
```

Exception:

- `POST /internal/v1/payments/webhook` does not require `X-Internal-Auth`.
- The webhook requires a payment signature header instead.

If the internal auth header is missing:

```json
{
  "status": "FAILURE",
  "message": "Missing X-Internal-Auth header"
}
```

## Public Endpoints

### POST `/payments/initiate`

Creates a local payment record and a provider order.

Headers:

```http
Authorization: Bearer <jwt>
Idempotency-Key: <unique-key>
Content-Type: application/json
```

Request body:

```json
{
  "eventId": "2ce4e42f-48f1-4a84-b409-fec7f2a9c8df",
  "lockId": "45fd0ef3-4c58-45b1-a2dd-1302b9c17d20",
  "provider": "RAZORPAY"
}
```

Validation:

- `eventId` is required
- `lockId` is required
- `provider` is required

Success response: `201 Created`

```json
{
  "status": "SUCCESS",
  "message": "Payment initiated successfully",
  "payment": {
    "paymentId": "7a1f18c7-7692-41d8-876c-25f7b2fdde3f",
    "eventId": "2ce4e42f-48f1-4a84-b409-fec7f2a9c8df",
    "lockId": "45fd0ef3-4c58-45b1-a2dd-1302b9c17d20",
    "amountMinor": 0,
    "currency": "INR",
    "status": "PENDING",
    "providerKeyId": "rzp_test_xxx",
    "providerOrderId": "order_Rzp123",
    "providerPaymentId": null
  }
}
```

Behavior notes:

- The service uses idempotency based on `Idempotency-Key`.
- If the same key is reused with the same request shape, the existing payment is returned.
- If the same key is reused with a different request shape, the request is rejected.
- Current implementation hardcodes `amountMinor` to `0`.

Failure response: `409 Conflict`

```json
{
  "status": "FAILURE",
  "message": "Idempotency key already used for a different request"
}
```

Failure response: `400 Bad Request`

```json
{
  "status": "FAILURE",
  "message": "Stored idempotent payment not found"
}
```

### GET `/payments/{paymentId}`

Fetches the current payment state.

Headers:

```http
Authorization: Bearer <jwt>
```

Success response: `200 OK`

```json
{
  "status": "SUCCESS",
  "message": "Payment status fetched successfully",
  "payment": {
    "paymentId": "7a1f18c7-7692-41d8-876c-25f7b2fdde3f",
    "eventId": "2ce4e42f-48f1-4a84-b409-fec7f2a9c8df",
    "lockId": "45fd0ef3-4c58-45b1-a2dd-1302b9c17d20",
    "amountMinor": 0,
    "currency": "INR",
    "status": "PENDING",
    "providerKeyId": "rzp_test_xxx",
    "providerOrderId": "order_Rzp123",
    "providerPaymentId": "pay_Rzp123"
  }
}
```

Failure response: `404 Not Found`

```json
{
  "status": "FAILURE",
  "message": "Payment not found for id: 7a1f18c7-7692-41d8-876c-25f7b2fdde3f"
}
```

### POST `/payments/{paymentId}/cancel`

Cancels a payment if its current state is `CREATED` or `PENDING`.

Headers:

```http
Authorization: Bearer <jwt>
```

Success response: `200 OK`

```json
{
  "status": "SUCCESS",
  "message": "Payment cancelled successfully",
  "paymentCancellation": {
    "paymentId": "7a1f18c7-7692-41d8-876c-25f7b2fdde3f",
    "status": "CANCELLED",
    "lockReleased": true
  }
}
```

Behavior notes:

- For non-cancellable terminal states, the API still returns `200 OK`.
- In that case, the current payment status is returned unchanged and `lockReleased` is `false`.
- The actual lock service integration is not implemented yet.

Failure response: `404 Not Found`

```json
{
  "status": "FAILURE",
  "message": "Payment not found for id: 7a1f18c7-7692-41d8-876c-25f7b2fdde3f"
}
```

### POST `/payments/{paymentId}/verify`

Verifies a client-side payment signature against the provider secret.

Headers:

```http
Authorization: Bearer <jwt>
Content-Type: application/json
```

Request body:

```json
{
  "providerOrderId": "order_Rzp123",
  "providerPaymentId": "pay_Rzp123",
  "providerSignature": "abcdef1234567890"
}
```

Validation:

- `providerOrderId` is required
- `providerPaymentId` is required
- `providerSignature` is required

Success response: `200 OK`

```json
{
  "status": "SUCCESS",
  "message": "Payment verification accepted",
  "payment": {
    "paymentId": "7a1f18c7-7692-41d8-876c-25f7b2fdde3f",
    "eventId": "2ce4e42f-48f1-4a84-b409-fec7f2a9c8df",
    "lockId": "45fd0ef3-4c58-45b1-a2dd-1302b9c17d20",
    "amountMinor": 0,
    "currency": "INR",
    "status": "PENDING",
    "providerKeyId": "rzp_test_xxx",
    "providerOrderId": "order_Rzp123",
    "providerPaymentId": "pay_Rzp123"
  }
}
```

Current implementation note:

- A successful verify call does not transition the payment to `SUCCESS`.
- Final status is expected to come from webhook processing.

Failure response: `400 Bad Request`

```json
{
  "status": "FAILURE",
  "message": "Provider payment signature verification failed"
}
```

Failure response: `404 Not Found`

```json
{
  "status": "FAILURE",
  "message": "Payment not found for id: 7a1f18c7-7692-41d8-876c-25f7b2fdde3f"
}
```

## Internal Endpoints

### POST `/internal/v1/payments/webhook`

Processes a provider webhook.

Headers:

```http
Content-Type: application/json
X-Payment-Provider: razorpay
X-Payment-Signature: <signature>
```

Razorpay-specific alternative header:

```http
X-Razorpay-Signature: <signature>
```

Provider resolution:

- If `X-Payment-Provider` is missing, provider defaults to `RAZORPAY`.
- If `X-Payment-Signature` is present, it is used.
- Otherwise, for Razorpay, `X-Razorpay-Signature` is used.

Minimal accepted Razorpay payload shape:

```json
{
  "event": "payment.captured",
  "payload": {
    "payment": {
      "entity": {
        "id": "pay_Rzp123",
        "order_id": "order_Rzp123",
        "status": "captured",
        "notes": {
          "paymentId": "7a1f18c7-7692-41d8-876c-25f7b2fdde3f"
        }
      }
    }
  }
}
```

Payment lookup order:

1. `notes.paymentId` or `notes.payment_id`
2. `providerPaymentId`
3. `providerOrderId`

Status mapping rules:

- events containing `confirmation_failed` -> `SUCCESS_CONFIRMATION_FAILED`
- events containing `refund` -> `REFUNDED`
- events containing `fail` or provider status containing `fail` -> `FAILED`
- events containing `cancel` -> `CANCELLED`
- events containing `success`, `capture`, `paid`, or provider status `captured` or `authorized` -> `SUCCESS`
- events containing `pending`, `author`, `create`, or provider status `created` -> `PENDING`
- otherwise current status is retained

Success response: `200 OK`

```json
{
  "status": "SUCCESS",
  "message": "Webhook processed successfully",
  "provider": "RAZORPAY",
  "providerEventId": "payment.captured:pay_Rzp123:captured",
  "duplicate": false,
  "eventType": "payment.captured",
  "eventId": "pay_Rzp123",
  "paymentId": "7a1f18c7-7692-41d8-876c-25f7b2fdde3f",
  "paymentStatus": "SUCCESS",
  "bookingConfirmationTriggered": true
}
```

Current implementation note:

- `bookingConfirmationTriggered` is a derived flag in the response.
- No actual booking confirmation integration is executed yet.

Failure response: `400 Bad Request`

```json
{
  "status": "FAILURE",
  "message": "Missing payment signature header"
}
```

Failure response: `400 Bad Request`

```json
{
  "status": "FAILURE",
  "message": "Unsupported payment provider: foo"
}
```

Failure response: `400 Bad Request`

```json
{
  "status": "FAILURE",
  "message": "Invalid webhook payload"
}
```

Failure response: `400 Bad Request`

```json
{
  "status": "FAILURE",
  "message": "Payment is not found with "
}
```

Failure response: `401 Unauthorized`

```json
{
  "status": "FAILURE",
  "message": "Invalid webhook signature"
}
```

Failure response: `409 Conflict`

```json
{
  "status": "FAILURE",
  "message": "Duplicate webhook received with providerEventId: payment.captured:pay_Rzp123:captured"
}
```

### POST `/internal/v1/payments/{paymentId}/reconcile`

Reconciles a payment after the webhook-driven success path.

Headers:

```http
X-Internal-Auth: <shared-secret>
```

Eligible starting statuses:

- `SUCCESS`
- `SUCCESS_CONFIRMATION_FAILED`

Success response: `200 OK`

```json
{
  "status": "SUCCESS",
  "message": "Booking confirmation completed successfully",
  "paymentId": "7a1f18c7-7692-41d8-876c-25f7b2fdde3f",
  "paymentStatus": "SUCCESS",
  "bookingConfirmationTriggered": true,
  "refunded": false,
  "lockReleased": false
}
```

Failure response for ineligible state: `400 Bad Request`

```json
{
  "status": "FAILURE",
  "message": "Payment is not eligible for reconciliation",
  "paymentId": "7a1f18c7-7692-41d8-876c-25f7b2fdde3f",
  "paymentStatus": "FAILED",
  "bookingConfirmationTriggered": false,
  "refunded": false,
  "lockReleased": false
}
```

Failure response when confirmation fails during reconcile: `400 Bad Request`

```json
{
  "status": "FAILURE",
  "message": "Booking confirmation failed. Refund triggered and lock released",
  "paymentId": "7a1f18c7-7692-41d8-876c-25f7b2fdde3f",
  "paymentStatus": "REFUNDED",
  "bookingConfirmationTriggered": true,
  "refunded": true,
  "lockReleased": true
}
```

Failure response: `404 Not Found`

```json
{
  "status": "FAILURE",
  "message": "Payment not found for id: 7a1f18c7-7692-41d8-876c-25f7b2fdde3f",
  "paymentId": "7a1f18c7-7692-41d8-876c-25f7b2fdde3f"
}
```

Current implementation notes:

- `triggerBookingConfirmation`, `triggerRefund`, and `releaseLock` are placeholders.
- Reconcile currently models intended outcomes in local payment state and response payloads only.
