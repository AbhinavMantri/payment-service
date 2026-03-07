package com.example.payment_service.model;

import lombok.Data;

import java.util.UUID;

@Data
public class PaymentSummary {
    private UUID paymentId;
    private UUID eventId;
    private UUID lockId;
    private Long amountMinor;
    private String currency;
    private PaymentStatus status;
    private String providerOrderId;
    private String providerPaymentId;
}
