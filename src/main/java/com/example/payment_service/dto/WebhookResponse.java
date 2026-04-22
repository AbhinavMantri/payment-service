package com.example.payment_service.dto;

import com.example.payment_service.dto.common.ApiResponse;
import com.example.payment_service.model.PaymentProvider;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@SuperBuilder(toBuilder = true)
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
