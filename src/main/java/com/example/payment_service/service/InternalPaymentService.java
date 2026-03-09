package com.example.payment_service.service;

import com.example.payment_service.dto.PaymentReconcileResponse;
import com.example.payment_service.dto.WebhookRequest;
import com.example.payment_service.dto.WebhookResponse;
import com.example.payment_service.dto.common.ResponseStatus;
import com.example.payment_service.exceptions.DuplicatePaymentFoundException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.exceptions.PaymentVerificationFailedException;
import com.example.payment_service.gateway.PaymentGateway;
import com.example.payment_service.gateway.PaymentGatewayRegistry;
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
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class InternalPaymentService {
    private final PaymentRepository paymentRepository;
    private final ProcessedWebhookRepository processedWebhookRepository;
    private final PaymentGatewayRegistry paymentGatewayRegistry;
    private final ObjectMapper objectMapper;

    @Autowired
    public InternalPaymentService(
            PaymentRepository paymentRepository,
            ProcessedWebhookRepository processedWebhookRepository,
            PaymentGatewayRegistry paymentGatewayRegistry,
            ObjectMapper objectMapper
    ) {
        this.paymentRepository = paymentRepository;
        this.processedWebhookRepository = processedWebhookRepository;
        this.paymentGatewayRegistry = paymentGatewayRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WebhookResponse handleWebhook(String rawPayload, String signature) throws PaymentNotFoundException, DuplicatePaymentFoundException {
        PaymentProvider provider = PaymentProvider.RAZORPAY;
        PaymentGateway paymentGateway = paymentGatewayRegistry.get(provider);
        if (!paymentGateway.verifyWebhookSignature(rawPayload, signature)) {
            throw new PaymentVerificationFailedException("Invalid webhook signature");
        }

        WebhookRequest request = parseWebhookRequest(rawPayload);
        WebhookRequest.Entity entity = extractEntity(request);
        String eventType = request.getEvent();
        String providerEventId = buildProviderEventId(eventType, entity);
        log.info(
                "internal-webhook service_start eventType={} provider={} providerEventId={} providerPaymentId={} providerOrderId={}",
                eventType,
                provider,
                providerEventId,
                entity.getId(),
                entity.getOrderId()
        );

        Optional<ProcessedWebhook> existingWebhook =
                processedWebhookRepository.findByProviderAndProviderEventId(provider.name(), providerEventId);
        if (existingWebhook.isPresent()) {
            log.warn("internal-webhook duplicate provider={} providerEventId={}", provider, providerEventId);
            throw new DuplicatePaymentFoundException("Duplicate webhook received with providerEventId: " + providerEventId);
        }

        Payment payment = resolvePayment(entity)
            .orElseThrow(() -> new PaymentNotFoundException("Payment is not found with "));
        log.info(
                "internal-webhook payment_resolved paymentId={} currentStatus={} providerPaymentId={} providerOrderId={}",
                payment.getId(),
                payment.getStatus(),
                payment.getProviderPaymentId(),
                payment.getProviderOrderId()
        );

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
        log.info(
                "internal-webhook payment_updated paymentId={} nextStatus={} bookingConfirmationTriggered={}",
                payment.getId(),
                nextStatus,
                nextStatus == PaymentStatus.SUCCESS || nextStatus == PaymentStatus.SUCCESS_CONFIRMATION_FAILED
        );

        ProcessedWebhook processedWebhook = new ProcessedWebhook();
        processedWebhook.setProvider(provider.name());
        processedWebhook.setProviderEventId(providerEventId);
        processedWebhookRepository.save(processedWebhook);
        log.info("internal-webhook webhook_recorded provider={} providerEventId={}", provider, providerEventId);

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

    private WebhookRequest parseWebhookRequest(String rawPayload) {
        try {
            return objectMapper.readValue(rawPayload, WebhookRequest.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid webhook payload", ex);
        }
    }

    @Transactional
    public PaymentReconcileResponse reconcilePayment(UUID paymentId) {
        log.info("internal-reconcile service_start paymentId={}", paymentId);
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for id: " + paymentId));
        log.info(
                "internal-reconcile payment_loaded paymentId={} paymentStatus={} lockId={}",
                paymentId,
                payment.getStatus(),
                payment.getLockId()
        );

        PaymentReconcileResponse response = new PaymentReconcileResponse();
        response.setPaymentId(paymentId);

        if (payment.getStatus() != PaymentStatus.SUCCESS
                && payment.getStatus() != PaymentStatus.SUCCESS_CONFIRMATION_FAILED) {
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage("Payment is not eligible for reconciliation");
            response.setPaymentStatus(payment.getStatus());
            log.warn(
                    "internal-reconcile payment_ineligible paymentId={} paymentStatus={}",
                    paymentId,
                    payment.getStatus()
            );
            return response;
        }

        response.setBookingConfirmationTriggered(true);
        log.info("internal-reconcile booking_confirmation_attempt paymentId={} paymentStatus={}", paymentId, payment.getStatus());

        try {
            triggerBookingConfirmation(payment);
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);
            response.setStatus(ResponseStatus.SUCCESS);
            response.setMessage("Booking confirmation completed successfully");
            response.setPaymentStatus(payment.getStatus());
            log.info("internal-reconcile booking_confirmation_success paymentId={} paymentStatus={}", paymentId, payment.getStatus());
            return response;
        } catch (RuntimeException ex) {
            log.warn("internal-reconcile booking_confirmation_failed paymentId={} reason={}", paymentId, ex.getMessage());
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
            log.warn(
                    "internal-reconcile refund_completed paymentId={} paymentStatus={} lockReleased={}",
                    paymentId,
                    payment.getStatus(),
                    true
            );
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
