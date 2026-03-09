package com.example.payment_service.controller;

import com.example.payment_service.dto.PaymentReconcileResponse;
import com.example.payment_service.dto.WebhookResponse;
import com.example.payment_service.dto.common.ResponseStatus;
import com.example.payment_service.exceptions.DuplicatePaymentFoundException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.exceptions.PaymentVerificationFailedException;
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
            @RequestHeader("X-Razorpay-Signature") String signature
    ) {
        long startNanos = System.nanoTime();
        log.info("internal-webhook request_start signaturePresent={}", signature != null && !signature.isBlank());

        WebhookResponse response = new WebhookResponse();
        try {
            response = internalPaymentService.handleWebhook(rawPayload, signature);
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
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage(ex.getMessage());
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
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage(ex.getMessage());
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
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage(ex.getMessage());
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
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage(ex.getMessage());
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
            PaymentReconcileResponse response = internalPaymentService.reconcilePayment(paymentId);
            HttpStatus httpStatus = response.getStatus() == ResponseStatus.SUCCESS ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
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
}
