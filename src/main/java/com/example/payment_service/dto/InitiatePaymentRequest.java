package com.example.payment_service.dto;

import com.example.payment_service.model.PaymentProvider;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class InitiatePaymentRequest {
    @NotNull(message = "Event ID is required")
    private UUID eventId;

    @NotNull(message = "Lock ID is required")
    private UUID lockId;

    @NotNull(message = "Provider is required")
    private PaymentProvider provider;
}
