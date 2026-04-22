package com.example.payment_service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PaymentVerificationRequest {
    @NotBlank(message = "Provider order ID is required")
    private String providerOrderId;

    @NotBlank(message = "Provider payment ID is required")
    private String providerPaymentId;

    @NotBlank(message = "Provider signature is required")
    private String providerSignature;
}
