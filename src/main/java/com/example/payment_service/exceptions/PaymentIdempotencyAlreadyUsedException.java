package com.example.payment_service.exceptions;

public class PaymentIdempotencyAlreadyUsedException extends RuntimeException {
    public PaymentIdempotencyAlreadyUsedException(String message) {
        super(message);
    }
}
