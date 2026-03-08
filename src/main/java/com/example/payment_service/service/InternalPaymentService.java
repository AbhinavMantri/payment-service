package com.example.payment_service.service;

import com.example.payment_service.dto.PaymentReconcileResponse;
import com.example.payment_service.dto.WebhookRequest;
import com.example.payment_service.dto.WebhookResponse;
import com.example.payment_service.dto.common.ResponseStatus;
import com.example.payment_service.exceptions.DuplicatePaymentFoundException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.model.Payment;
import com.example.payment_service.model.PaymentProvider;
import com.example.payment_service.model.PaymentStatus;
import com.example.payment_service.model.ProcessedWebhook;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.repository.ProcessedWebhookRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class InternalPaymentService {
    private final PaymentRepository paymentRepository;
    private final ProcessedWebhookRepository processedWebhookRepository;

    @Autowired
    public InternalPaymentService(
            PaymentRepository paymentRepository,
            ProcessedWebhookRepository processedWebhookRepository
    ) {
        this.paymentRepository = paymentRepository;
        this.processedWebhookRepository = processedWebhookRepository;
    }

    @Transactional
    public WebhookResponse handleWebhook(WebhookRequest request) throws PaymentNotFoundException, DuplicatePaymentFoundException {
        WebhookRequest.Entity entity = extractEntity(request);
        String eventType = request.getEvent();

        PaymentProvider provider = PaymentProvider.RAZORPAY;
        String providerEventId = buildProviderEventId(eventType, entity);

        Optional<ProcessedWebhook> existingWebhook =
                processedWebhookRepository.findByProviderAndProviderEventId(provider.name(), providerEventId);
        if (existingWebhook.isPresent()) {
            throw new DuplicatePaymentFoundException("Duplicate webhook received with providerEventId: " + providerEventId);
        }

        Payment payment = resolvePayment(entity)
            .orElseThrow(() -> new PaymentNotFoundException("Payment is not found with "));

        if (entity.getId() != null && !entity.getId().isBlank()) {
            payment.setProviderPaymentId(entity.getId());
        }
        if (entity.getOrderId() != null && !entity.getOrderId().isBlank()) {
            payment.setProviderOrderId(entity.getOrderId());
        }

        PaymentStatus nextStatus = determineStatus(eventType, entity.getStatus(), payment.getStatus());
        payment.setStatus(nextStatus);
        if (nextStatus != PaymentStatus.FAILED) {
            payment.setFailureReason(null);
        }
        paymentRepository.save(payment);

        ProcessedWebhook processedWebhook = new ProcessedWebhook();
        processedWebhook.setProvider(provider.name());
        processedWebhook.setProviderEventId(providerEventId);
        processedWebhookRepository.save(processedWebhook);

        boolean bookingConfirmationTriggered =
                nextStatus == PaymentStatus.SUCCESS || nextStatus == PaymentStatus.SUCCESS_CONFIRMATION_FAILED;

        return toResponse(
                ResponseStatus.SUCCESS,
                "Webhook processed successfully",
                provider,
                providerEventId,
                eventType,
                entity.getId(),
                payment,
                false,
                bookingConfirmationTriggered
        );
    }

    @Transactional
    public PaymentReconcileResponse reconcilePayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for id: " + paymentId));

        PaymentReconcileResponse response = new PaymentReconcileResponse();
        response.setPaymentId(paymentId);

        if (payment.getStatus() != PaymentStatus.SUCCESS
                && payment.getStatus() != PaymentStatus.SUCCESS_CONFIRMATION_FAILED) {
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage("Payment is not eligible for reconciliation");
            response.setPaymentStatus(payment.getStatus());
            return response;
        }

        response.setBookingConfirmationTriggered(true);

        try {
            triggerBookingConfirmation(payment);
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);
            response.setStatus(ResponseStatus.SUCCESS);
            response.setMessage("Booking confirmation completed successfully");
            response.setPaymentStatus(payment.getStatus());
            return response;
        } catch (RuntimeException ex) {
            triggerRefund(payment);
            releaseLock(payment);
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setFailureReason(ex.getMessage());
            paymentRepository.save(payment);

            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage("Booking confirmation failed. Refund triggered and lock released");
            response.setPaymentStatus(payment.getStatus());
            response.setRefunded(true);
            response.setLockReleased(true);
            return response;
        }
    }

    private Optional<Payment> resolvePayment(WebhookRequest.Entity entity) {
        String internalPaymentId = extractInternalPaymentId(entity);
        if (internalPaymentId != null) {
            try {
                return paymentRepository.findById(UUID.fromString(internalPaymentId));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed internal payment id and fall back to provider identifiers.
            }
        }

        if (entity.getId() != null && !entity.getId().isBlank()) {
            Optional<Payment> byProviderPaymentId = paymentRepository.findByProviderPaymentId(entity.getId());
            if (byProviderPaymentId.isPresent()) {
                return byProviderPaymentId;
            }
        }

        if (entity.getOrderId() != null && !entity.getOrderId().isBlank()) {
            return paymentRepository.findByProviderOrderId(entity.getOrderId());
        }

        return Optional.empty();
    }

    private PaymentStatus determineStatus(String eventType, String providerStatus, PaymentStatus currentStatus) {
        String normalizedEventType = eventType.toLowerCase(Locale.ROOT);
        String normalizedProviderStatus = providerStatus == null ? "" : providerStatus.toLowerCase(Locale.ROOT);

        if (normalizedEventType.contains("confirmation_failed")) {
            return PaymentStatus.SUCCESS_CONFIRMATION_FAILED;
        }
        if (normalizedEventType.contains("refund")) {
            return PaymentStatus.REFUNDED;
        }
        if (normalizedEventType.contains("fail") || normalizedProviderStatus.contains("fail")) {
            return PaymentStatus.FAILED;
        }
        if (normalizedEventType.contains("cancel")) {
            return PaymentStatus.CANCELLED;
        }
        if (normalizedEventType.contains("success")
                || normalizedEventType.contains("capture")
                || normalizedEventType.contains("paid")
                || "captured".equals(normalizedProviderStatus)
                || "authorized".equals(normalizedProviderStatus)) {
            return PaymentStatus.SUCCESS;
        }
        if (normalizedEventType.contains("pending")
                || normalizedEventType.contains("author")
                || normalizedEventType.contains("create")
                || "created".equals(normalizedProviderStatus)) {
            return PaymentStatus.PENDING;
        }
        return currentStatus;
    }

    private WebhookRequest.Entity extractEntity(WebhookRequest request) {
        return request.getPayload().getPayment().getEntity();
    }

    private String extractInternalPaymentId(WebhookRequest.Entity entity) {
        if (entity.getNotes() == null) {
            return null;
        }
        return firstNonBlank(entity.getNotes().get("paymentId"), entity.getNotes().get("payment_id"));
    }

    private String buildProviderEventId(String eventType, WebhookRequest.Entity entity) {
        return eventType + ":" + firstNonBlank(entity.getId(), entity.getOrderId(), "unknown") + ":"
                + firstNonBlank(entity.getStatus(), "unknown");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private WebhookResponse toResponse(
            ResponseStatus status,
            String message,
            PaymentProvider provider,
            String providerEventId,
            String eventType,
            String eventId,
            Payment payment,
            boolean duplicate,
            boolean bookingConfirmationTriggered
    ) {
        WebhookResponse response = new WebhookResponse();
        response.setStatus(status);
        response.setMessage(message);
        response.setProvider(provider);
        response.setProviderEventId(providerEventId);
        response.setDuplicate(duplicate);
        response.setEventType(eventType);
        response.setEventId(eventId);
        response.setPaymentId(payment == null ? null : payment.getId().toString());
        response.setPaymentStatus(payment == null ? null : payment.getStatus().name());
        response.setBookingConfirmationTriggered(bookingConfirmationTriggered);
        return response;
    }

    private void triggerBookingConfirmation(Payment payment) {
        if (payment.getStatus() == PaymentStatus.SUCCESS_CONFIRMATION_FAILED
                && payment.getFailureReason() != null
                && !payment.getFailureReason().isBlank()) {
            throw new IllegalStateException(payment.getFailureReason());
        }
    }

    private void triggerRefund(Payment payment) {
        // Placeholder for refund integration.
    }

    private void releaseLock(Payment payment) {
        // Placeholder for lock release integration.
    }
}
