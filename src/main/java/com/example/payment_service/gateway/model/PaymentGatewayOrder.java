package com.example.payment_service.gateway.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PaymentGatewayOrder {
    String orderId;
    String publicKey;
    Long amountMinor;
    String currency;
}
