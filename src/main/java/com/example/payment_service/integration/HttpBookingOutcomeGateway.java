package com.example.payment_service.integration;

import com.example.payment_service.model.Payment;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpBookingOutcomeGateway implements BookingOutcomeGateway {
    private final RestClient restClient;

    public HttpBookingOutcomeGateway(
            RestClient.Builder restClientBuilder,
            @Value("${integration.booking.base-url:http://localhost:8082/booking-service/v1}") String baseUrl
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public void notifyPaymentOutcome(Payment payment) {
        PaymentOutcomePayload payload = new PaymentOutcomePayload();
        payload.setPaymentId(payment.getId());
        payload.setUserId(payment.getUserId());
        payload.setEventId(payment.getEventId());
        payload.setLockId(payment.getLockId());
        payload.setTotalAmountMinor(payment.getAmountMinor());
        payload.setCurrency(payment.getCurrency());
        payload.setPaymentStatus(payment.getStatus().name());
        restClient.post()
                .uri("/internal/bookings/payment-outcomes")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    @Data
    private static class PaymentOutcomePayload {
        private java.util.UUID paymentId;
        private java.util.UUID userId;
        private java.util.UUID eventId;
        private java.util.UUID lockId;
        private Long totalAmountMinor;
        private String currency;
        private String paymentStatus;
    }
}
