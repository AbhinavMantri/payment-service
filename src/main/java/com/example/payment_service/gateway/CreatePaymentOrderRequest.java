package com.example.payment_service.gateway;

import com.example.payment_service.model.PaymentProvider;
import lombok.Builder;
import lombok.Value;

import java.util.Map;
import java.util.UUID;

@Value
@Builder
public class CreatePaymentOrderRequest {
    UUID paymentId;
    Long amountMinor;
    String currency;
    PaymentProvider provider;
    Map<String, String> notes;
}
