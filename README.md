# Payment Service
## Purpose

payment-service manages the payment lifecycle for ticket bookings.
It integrates with payment providers, tracks payment status, processes webhooks, and confirms seat bookings through the seat-allocation-service.

It does not manage seats or events; those belong to other services.

## Entities

### Payment
Represents a payment attempt initiated for locked seats.


#### Fields

| Field	  | Type |	Description
|:------------------|:----------:|--------------:|
| id	              | UUID       |  Primary identifier
| userId	          | UUID       |	User initiating payment
| eventId	          | UUID       |	Associated event
| lockId	          | UUID       |	Seat lock reference
| amountMinor	      | BIGINT	   |  Amount in minor units of currency
| currency	        | VARCHAR(3) |	ISO currency code
| provider	        | VARCHAR	   | Payment provider
| status	          | VARCHAR	   | Current payment status
| providerOrderId   |	VARCHAR	   | Order reference from payment gateway
| providerPaymentId	| VARCHAR	   | Payment reference from provider
| failureReason	    | VARCHAR	   | Failure message if payment fails
| createdAt	        | TIMESTAMP	 | Creation time
| updatedAt	        | TIMESTAMP	 | Last update time

### PaymentStatus

- CREATED
- PENDING
- SUCCESS
- FAILED
- CANCELLED
- SUCCESS_CONFIRMATION_FAILED
- REFUNDED

### PaymentIdempotency

Stores idempotency keys for safe retry of payment initiation.

| Field	| Type |	Description
|:------------------|:----------:|--------------:|
| id	| UUID	| Primary identifier
| userId |	UUID |	Requesting user
| idempotencyKey |	VARCHAR |	Client provided key
| requestHash	 | VARCHAR |	Hash of request payload
| paymentId |	UUID |	Associated payment
| createdAt	| TIMESTAMP |	Record creation time

### ProcessedWebhook

Tracks provider webhook events that have already been processed.

| Field |	Type |	Description
|:------------------|:----------:|--------------:|
| id	| UUID	| Primary identifier
| provider |	VARCHAR |	Payment provider
|providerEventId	| VARCHAR	| Unique provider event id
| processedAt	| TIMESTAMP |	Processing time

Purpose: prevents duplicate webhook handling.

## API Contract

### Public APIs

#### Initiate Payment
Creates a payment attempt for locked seats.

POST
/api/v1/payments/initiate

Headers

Authorization: Bearer <JWT>
Idempotency-Key: <unique-key>

Request

console.log('{
  "eventId": "uuid",
  "lockId": "uuid",
  "provider": "RAZORPAY"
}')

Response

{
  "paymentId": "uuid",
  "eventId": "uuid",
  "lockId": "uuid",
  "amountMinor": 350000,
  "currency": "INR",
  "status": "PENDING",
  "providerOrderId": "order_12345"
}
Get Payment Status

GET

/api/v1/payments/{paymentId}

Headers

Authorization: Bearer <JWT>

Response

{
  "paymentId": "uuid",
  "eventId": "uuid",
  "lockId": "uuid",
  "amountMinor": 350000,
  "currency": "INR",
  "status": "SUCCESS",
  "providerPaymentId": "pay_abc123"
}

Cancel Payment Attempt

POST

/api/v1/payments/{paymentId}/cancel

Cancels a payment that has not yet completed.

Internal APIs
Payment Webhook (Provider Callback)

POST

/internal/v1/payments/webhook

Purpose:

verify provider signature

update payment status

trigger seat booking confirmation

Reconcile Payment

POST

/internal/v1/payments/{paymentId}/reconcile

Purpose:

retry booking confirmation if payment succeeded but downstream booking failed.

# Internal Service Calls
Seat Allocation → Payment

Not required.

Payment → Seat Allocation
Get Lock Summary
GET /internal/v1/locks/{lockId}/summary

Returns authoritative payable amount.

Confirm Booking
POST /internal/v1/locks/{lockId}/confirm-booking

Used after payment success.

Request

{
  "paymentId": "uuid",
  "providerPaymentId": "pay_abc123"
}