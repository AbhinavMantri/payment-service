package com.example.payment_service.exceptions;

public class DuplicatePaymentFoundException extends RuntimeException {
    public DuplicatePaymentFoundException(String message) {
        super(message);
    }
}
