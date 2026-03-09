package com.example.payment_service.service;

import com.example.payment_service.model.PaymentStatus;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class ReconcilePaymentResult {
    UUID paymentId;
    PaymentStatus paymentStatus;
    boolean success;
    boolean bookingConfirmationTriggered;
    boolean refunded;
    boolean lockReleased;
    String message;
}
