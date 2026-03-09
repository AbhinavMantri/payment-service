package com.example.payment_service.controller;

import com.example.payment_service.dto.PaymentReconcileResponse;
import com.example.payment_service.dto.WebhookResponse;
import com.example.payment_service.dto.common.ResponseStatus;
import com.example.payment_service.exceptions.DuplicatePaymentFoundException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.exceptions.PaymentVerificationFailedException;
import com.example.payment_service.model.PaymentProvider;
import com.example.payment_service.service.ReconcilePaymentResult;
import com.example.payment_service.service.InternalPaymentService;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/payments")
@Slf4j
public class InternalPaymentController {
    private final InternalPaymentService internalPaymentService;

    @Autowired
    public InternalPaymentController(InternalPaymentService internalPaymentService) {
        this.internalPaymentService = internalPaymentService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<WebhookResponse> handleWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-Payment-Provider", required = false) String providerHeader,
            @RequestHeader(value = "X-Payment-Signature", required = false) String genericSignature,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String razorpaySignature
    ) {
        long startNanos = System.nanoTime();
        try {
            PaymentProvider provider = resolveProvider(providerHeader);
            String signature = resolveSignature(provider, genericSignature, razorpaySignature);
            log.info("internal-webhook request_start signaturePresent={}", signature != null && !signature.isBlank());
            WebhookResponse response = internalPaymentService.handleWebhook(provider, rawPayload, signature);
            log.info(
                    "internal-webhook request_end eventType={} providerPaymentId={} status={} httpStatus={} latencyMs={}",
                    response.getEventType(),
                    response.getEventId(),
                    response.getStatus(),
                    HttpStatus.OK.value(),
                    toLatencyMillis(startNanos)
            );
            return ResponseEntity.ok(response);
        } catch (PaymentNotFoundException ex) {
            WebhookResponse response = failureWebhookResponse(ex.getMessage());
            log.warn(
                    "internal-webhook request_end eventType={} providerPaymentId={} status={} httpStatus={} latencyMs={} reason={}",
                    null,
                    null,
                    response.getStatus(),
                    HttpStatus.BAD_REQUEST.value(),
                    toLatencyMillis(startNanos),
                    ex.getMessage()
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (DuplicatePaymentFoundException ex) {
            WebhookResponse response = failureWebhookResponse(ex.getMessage());
            log.warn(
                    "internal-webhook request_end eventType={} providerPaymentId={} status={} httpStatus={} latencyMs={} reason={}",
                    null,
                    null,
                    response.getStatus(),
                    HttpStatus.CONFLICT.value(),
                    toLatencyMillis(startNanos),
                    ex.getMessage()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        } catch (IllegalArgumentException ex) {
            WebhookResponse response = failureWebhookResponse(ex.getMessage());
            log.warn(
                    "internal-webhook request_end eventType={} providerPaymentId={} status={} httpStatus={} latencyMs={} reason={}",
                    null,
                    null,
                    response.getStatus(),
                    HttpStatus.BAD_REQUEST.value(),
                    toLatencyMillis(startNanos),
                    ex.getMessage()
            );
            return ResponseEntity.badRequest().body(response);
        } catch (PaymentVerificationFailedException ex) {
            WebhookResponse response = failureWebhookResponse(ex.getMessage());
            log.warn(
                    "internal-webhook request_end eventType={} providerPaymentId={} status={} httpStatus={} latencyMs={} reason={}",
                    null,
                    null,
                    response.getStatus(),
                    HttpStatus.UNAUTHORIZED.value(),
                    toLatencyMillis(startNanos),
                    ex.getMessage()
            );
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
    }

    @PostMapping("/{paymentId}/reconcile")
    public ResponseEntity<PaymentReconcileResponse> reconcilePayment(@PathVariable UUID paymentId) {
        long startNanos = System.nanoTime();
        log.info("internal-reconcile request_start paymentId={}", paymentId);
        try {
            ReconcilePaymentResult result = internalPaymentService.reconcilePayment(paymentId);
            PaymentReconcileResponse response = toReconcileResponse(result);
            HttpStatus httpStatus = result.isSuccess() ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
            log.info(
                    "internal-reconcile request_end paymentId={} status={} paymentStatus={} refunded={} lockReleased={} httpStatus={} latencyMs={}",
                    paymentId,
                    response.getStatus(),
                    response.getPaymentStatus(),
                    response.isRefunded(),
                    response.isLockReleased(),
                    httpStatus.value(),
                    toLatencyMillis(startNanos)
            );
            return ResponseEntity.status(httpStatus).body(response);
        } catch (PaymentNotFoundException ex) {
            PaymentReconcileResponse response = new PaymentReconcileResponse();
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage(ex.getMessage());
            response.setPaymentId(paymentId);
            log.warn(
                    "internal-reconcile request_end paymentId={} status={} httpStatus={} latencyMs={} reason={}",
                    paymentId,
                    response.getStatus(),
                    HttpStatus.NOT_FOUND.value(),
                    toLatencyMillis(startNanos),
                    ex.getMessage()
            );
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
    }

    private long toLatencyMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private PaymentProvider resolveProvider(String providerHeader) {
        if (providerHeader == null || providerHeader.isBlank()) {
            return PaymentProvider.RAZORPAY;
        }
        try {
            return PaymentProvider.valueOf(providerHeader.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported payment provider: " + providerHeader);
        }
    }

    private String resolveSignature(PaymentProvider provider, String genericSignature, String razorpaySignature) {
        if (genericSignature != null && !genericSignature.isBlank()) {
            return genericSignature;
        }
        if (provider == PaymentProvider.RAZORPAY && razorpaySignature != null && !razorpaySignature.isBlank()) {
            return razorpaySignature;
        }
        throw new IllegalArgumentException("Missing payment signature header");
    }

    private WebhookResponse failureWebhookResponse(String message) {
        return WebhookResponse.builder()
                .status(ResponseStatus.FAILURE)
                .message(message)
                .build();
    }

    private PaymentReconcileResponse toReconcileResponse(ReconcilePaymentResult result) {
        PaymentReconcileResponse response = new PaymentReconcileResponse();
        response.setPaymentId(result.getPaymentId());
        response.setPaymentStatus(result.getPaymentStatus());
        response.setBookingConfirmationTriggered(result.isBookingConfirmationTriggered());
        response.setRefunded(result.isRefunded());
        response.setLockReleased(result.isLockReleased());
        response.setMessage(result.getMessage());
        response.setStatus(result.isSuccess() ? ResponseStatus.SUCCESS : ResponseStatus.FAILURE);
        return response;
    }
}
