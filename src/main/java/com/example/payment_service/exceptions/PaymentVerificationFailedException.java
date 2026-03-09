package com.example.payment_service.exceptions;

public class PaymentVerificationFailedException extends RuntimeException {
    public PaymentVerificationFailedException(String message) {
        super(message);
    }
}
