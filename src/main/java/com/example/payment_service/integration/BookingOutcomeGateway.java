package com.example.payment_service.integration;

import com.example.payment_service.model.Payment;

public interface BookingOutcomeGateway {
    void notifyPaymentOutcome(Payment payment);
}
