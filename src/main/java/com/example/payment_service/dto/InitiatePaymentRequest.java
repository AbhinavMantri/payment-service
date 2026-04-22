package com.example.payment_service.dto;

import com.example.payment_service.model.PaymentProvider;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class InitiatePaymentRequest {
    @NotNull(message = "User ID is required")
    private UUID userId;

    @NotNull(message = "Event ID is required")
    private UUID eventId;

    @NotNull(message = "Lock ID is required")
    private UUID lockId;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than 0")
    private Long amountMinor;

    @NotNull(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
    private String currency;

    @NotNull(message = "Provider is required")
    private PaymentProvider provider;
}
