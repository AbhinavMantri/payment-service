package com.example.payment_service.gateway.model;

import com.example.payment_service.model.PaymentProvider;
import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class PaymentWebhookNotification {
    PaymentProvider provider;
    String eventType;
    String providerEventId;
    String providerPaymentId;
    String providerOrderId;
    String providerStatus;
    Map<String, String> notes;
}
