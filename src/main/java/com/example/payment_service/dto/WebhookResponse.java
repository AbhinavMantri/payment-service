package com.example.payment_service.dto;

import com.example.payment_service.dto.common.ApiResponse;
import com.example.payment_service.model.PaymentProvider;

import lombok.Data;

@Data
public class WebhookResponse extends ApiResponse {
    private PaymentProvider provider;
    private String providerEventId;
    private Boolean duplicate;
    private String eventType;
    private String eventId;
    private String paymentId;
    private String paymentStatus;
    private Boolean bookingConfirmationTriggered;
}
