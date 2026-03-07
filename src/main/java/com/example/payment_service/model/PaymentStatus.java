package com.example.payment_service.model;

public enum PaymentStatus {
    CREATED,
    PENDING,
    SUCCESS,
    FAILED,
    CANCELLED,
    SUCCESS_CONFIRMATION_FAILED,
    REFUNDED
}
