package com.example.payment_service.service;

import com.example.payment_service.dto.WebhookResponse;
import com.example.payment_service.dto.common.ResponseStatus;
import com.example.payment_service.exceptions.DuplicatePaymentFoundException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.exceptions.PaymentVerificationFailedException;
import com.example.payment_service.gateway.PaymentGateway;
import com.example.payment_service.gateway.PaymentGatewayRegistry;
import com.example.payment_service.gateway.model.PaymentWebhookNotification;
import com.example.payment_service.model.Payment;
import com.example.payment_service.model.PaymentProvider;
import com.example.payment_service.model.PaymentStatus;
import com.example.payment_service.model.ProcessedWebhook;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.repository.ProcessedWebhookRepository;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class InternalPaymentService {
    private final PaymentRepository paymentRepository;
    private final ProcessedWebhookRepository processedWebhookRepository;
    private final PaymentGatewayRegistry paymentGatewayRegistry;

    @Autowired
    public InternalPaymentService(
            PaymentRepository paymentRepository,
            ProcessedWebhookRepository processedWebhookRepository,
            PaymentGatewayRegistry paymentGatewayRegistry
    ) {
        this.paymentRepository = paymentRepository;
        this.processedWebhookRepository = processedWebhookRepository;
        this.paymentGatewayRegistry = paymentGatewayRegistry;
    }

    @Transactional
    public WebhookResponse handleWebhook(PaymentProvider provider, String rawPayload, String signature) throws PaymentNotFoundException, DuplicatePaymentFoundException {
        PaymentGateway paymentGateway = paymentGatewayRegistry.get(provider);
        if (!paymentGateway.verifyWebhookSignature(rawPayload, signature)) {
            throw new PaymentVerificationFailedException("Invalid webhook signature");
        }

        PaymentWebhookNotification notification = paymentGateway.parseWebhook(rawPayload);
        log.info(
                "internal-webhook service_start eventType={} provider={} providerEventId={} providerPaymentId={} providerOrderId={}",
                notification.getEventType(),
                provider,
                notification.getProviderEventId(),
                notification.getProviderPaymentId(),
                notification.getProviderOrderId()
        );

        Optional<ProcessedWebhook> existingWebhook =
                processedWebhookRepository.findByProviderAndProviderEventId(provider.name(), notification.getProviderEventId());
        if (existingWebhook.isPresent()) {
            log.warn("internal-webhook duplicate provider={} providerEventId={}", provider, notification.getProviderEventId());
            throw new DuplicatePaymentFoundException("Duplicate webhook received with providerEventId: " + notification.getProviderEventId());
        }

        Payment payment = resolvePayment(notification)
            .orElseThrow(() -> new PaymentNotFoundException("Payment is not found with "));
        log.info(
                "internal-webhook payment_resolved paymentId={} currentStatus={} providerPaymentId={} providerOrderId={}",
                payment.getId(),
                payment.getStatus(),
                payment.getProviderPaymentId(),
                payment.getProviderOrderId()
        );

        if (notification.getProviderPaymentId() != null && !notification.getProviderPaymentId().isBlank()) {
            payment.setProviderPaymentId(notification.getProviderPaymentId());
        }
        if (notification.getProviderOrderId() != null && !notification.getProviderOrderId().isBlank()) {
            payment.setProviderOrderId(notification.getProviderOrderId());
        }

        PaymentStatus nextStatus = determineStatus(notification.getEventType(), notification.getProviderStatus(), payment.getStatus());
        payment.setStatus(nextStatus);
        if (nextStatus != PaymentStatus.FAILED) {
            payment.setFailureReason(null);
        }
        paymentRepository.save(payment);
        log.info(
                "internal-webhook payment_updated paymentId={} nextStatus={} bookingConfirmationTriggered={}",
                payment.getId(),
                nextStatus,
                nextStatus == PaymentStatus.SUCCESS || nextStatus == PaymentStatus.SUCCESS_CONFIRMATION_FAILED
        );

        ProcessedWebhook processedWebhook = new ProcessedWebhook();
        processedWebhook.setProvider(provider.name());
        processedWebhook.setProviderEventId(notification.getProviderEventId());
        processedWebhookRepository.save(processedWebhook);
        log.info("internal-webhook webhook_recorded provider={} providerEventId={}", provider, notification.getProviderEventId());

        boolean bookingConfirmationTriggered =
                nextStatus == PaymentStatus.SUCCESS || nextStatus == PaymentStatus.SUCCESS_CONFIRMATION_FAILED;

        return toResponse(
                ResponseStatus.SUCCESS,
                "Webhook processed successfully",
                provider,
                notification.getProviderEventId(),
                notification.getEventType(),
                notification.getProviderPaymentId(),
                payment,
                false,
                bookingConfirmationTriggered
        );
    }

    @Transactional
    public ReconcilePaymentResult reconcilePayment(UUID paymentId) {
        log.info("internal-reconcile service_start paymentId={}", paymentId);
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for id: " + paymentId));
        log.info(
                "internal-reconcile payment_loaded paymentId={} paymentStatus={} lockId={}",
                paymentId,
                payment.getStatus(),
                payment.getLockId()
        );

        if (payment.getStatus() != PaymentStatus.SUCCESS
                && payment.getStatus() != PaymentStatus.SUCCESS_CONFIRMATION_FAILED) {
            log.warn(
                    "internal-reconcile payment_ineligible paymentId={} paymentStatus={}",
                    paymentId,
                    payment.getStatus()
            );
            return ReconcilePaymentResult.builder()
                    .paymentId(paymentId)
                    .paymentStatus(payment.getStatus())
                    .success(false)
                    .message("Payment is not eligible for reconciliation")
                    .build();
        }

        log.info("internal-reconcile booking_confirmation_attempt paymentId={} paymentStatus={}", paymentId, payment.getStatus());

        try {
            triggerBookingConfirmation(payment);
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);
            log.info("internal-reconcile booking_confirmation_success paymentId={} paymentStatus={}", paymentId, payment.getStatus());
            return ReconcilePaymentResult.builder()
                    .paymentId(paymentId)
                    .paymentStatus(payment.getStatus())
                    .success(true)
                    .bookingConfirmationTriggered(true)
                    .message("Booking confirmation completed successfully")
                    .build();
        } catch (RuntimeException ex) {
            log.warn("internal-reconcile booking_confirmation_failed paymentId={} reason={}", paymentId, ex.getMessage());
            triggerRefund(payment);
            releaseLock(payment);
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setFailureReason(ex.getMessage());
            paymentRepository.save(payment);

            log.warn(
                    "internal-reconcile refund_completed paymentId={} paymentStatus={} lockReleased={}",
                    paymentId,
                    payment.getStatus(),
                    true
            );
            return ReconcilePaymentResult.builder()
                    .paymentId(paymentId)
                    .paymentStatus(payment.getStatus())
                    .success(false)
                    .bookingConfirmationTriggered(true)
                    .refunded(true)
                    .lockReleased(true)
                    .message("Booking confirmation failed. Refund triggered and lock released")
                    .build();
        }
    }

    private Optional<Payment> resolvePayment(PaymentWebhookNotification notification) {
        String internalPaymentId = extractInternalPaymentId(notification.getNotes());
        if (internalPaymentId != null) {
            try {
                return paymentRepository.findById(UUID.fromString(internalPaymentId));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed internal payment id and fall back to provider identifiers.
            }
        }

        if (notification.getProviderPaymentId() != null && !notification.getProviderPaymentId().isBlank()) {
            Optional<Payment> byProviderPaymentId = paymentRepository.findByProviderPaymentId(notification.getProviderPaymentId());
            if (byProviderPaymentId.isPresent()) {
                return byProviderPaymentId;
            }
        }

        if (notification.getProviderOrderId() != null && !notification.getProviderOrderId().isBlank()) {
            return paymentRepository.findByProviderOrderId(notification.getProviderOrderId());
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

    private String extractInternalPaymentId(java.util.Map<String, String> notes) {
        if (notes == null) {
            return null;
        }
        return firstNonBlank(notes.get("paymentId"), notes.get("payment_id"));
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
        return WebhookResponse.builder()
                .status(status)
                .message(message)
                .provider(provider)
                .providerEventId(providerEventId)
                .duplicate(duplicate)
                .eventType(eventType)
                .eventId(eventId)
                .paymentId(payment == null ? null : payment.getId().toString())
                .paymentStatus(payment == null ? null : payment.getStatus().name())
                .bookingConfirmationTriggered(bookingConfirmationTriggered)
                .build();
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
