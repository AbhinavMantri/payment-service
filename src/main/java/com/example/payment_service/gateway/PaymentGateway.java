package com.example.payment_service.gateway;

import com.example.payment_service.dto.PaymentVerificationRequest;
import com.example.payment_service.model.PaymentProvider;

public interface PaymentGateway {
    PaymentProvider provider();

    String publicKey();

    PaymentGatewayOrder createOrder(CreatePaymentOrderRequest request);

    boolean verifyPaymentSignature(PaymentVerificationRequest request);

    boolean verifyWebhookSignature(String payload, String signature);
}
