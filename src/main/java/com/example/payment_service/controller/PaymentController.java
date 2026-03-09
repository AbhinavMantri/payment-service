package com.example.payment_service.controller;

import com.example.payment_service.dto.InitiatePaymentRequest;
import com.example.payment_service.dto.PaymentCancellation;
import com.example.payment_service.dto.PaymentCancelResponse;
import com.example.payment_service.dto.PaymentSummaryResponse;
import com.example.payment_service.dto.common.ResponseStatus;
import com.example.payment_service.exceptions.PaymentIdempotencyAlreadyUsedException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.model.PaymentSummary;
import com.example.payment_service.service.PaymentService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    @Autowired
    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/initiate")
    public ResponseEntity<PaymentSummaryResponse> initiatePayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid InitiatePaymentRequest request
    ) {
        long startNanos = System.nanoTime();
        log.info(
                "payment-initiate request_start idempotencyKey={} eventId={} lockId={} provider={}",
                idempotencyKey,
                request.getEventId(),
                request.getLockId(),
                request.getProvider()
        );
        PaymentSummaryResponse response = new PaymentSummaryResponse();

        try {
            PaymentSummary paymentSummary = paymentService.initiatePayment(idempotencyKey, request);
            response.setStatus(ResponseStatus.SUCCESS);
            response.setPayment(paymentSummary);
            response.setMessage("Payment initiated successfully");
            log.info(
                    "payment-initiate request_end idempotencyKey={} paymentId={} status={} httpStatus={} latencyMs={}",
                    idempotencyKey,
                    paymentSummary.getPaymentId(),
                    response.getStatus(),
                    HttpStatus.CREATED.value(),
                    toLatencyMillis(startNanos)
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(response);    
        } catch (PaymentNotFoundException ex) {
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage(ex.getMessage());
            log.warn(
                    "payment-initiate request_end idempotencyKey={} eventId={} lockId={} status={} httpStatus={} latencyMs={} reason={}",
                    idempotencyKey,
                    request.getEventId(),
                    request.getLockId(),
                    response.getStatus(),
                    HttpStatus.BAD_REQUEST.value(),
                    toLatencyMillis(startNanos),
                    ex.getMessage()
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (PaymentIdempotencyAlreadyUsedException ex) {
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage(ex.getMessage());
            log.warn(
                    "payment-initiate request_end idempotencyKey={} eventId={} lockId={} status={} httpStatus={} latencyMs={} reason={}",
                    idempotencyKey,
                    request.getEventId(),
                    request.getLockId(),
                    response.getStatus(),
                    HttpStatus.CONFLICT.value(),
                    toLatencyMillis(startNanos),
                    ex.getMessage()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentSummaryResponse> getPaymentStatus(@PathVariable UUID paymentId) {
        long startNanos = System.nanoTime();
        log.info("payment-status request_start paymentId={}", paymentId);
        PaymentSummaryResponse response = new PaymentSummaryResponse();

        try {
            PaymentSummary paymentSummary = paymentService.getPaymentStatus(paymentId);
            response.setStatus(ResponseStatus.SUCCESS);
            response.setPayment(paymentSummary);
            response.setMessage("Payment status fetched successfully");
            log.info(
                    "payment-status request_end paymentId={} status={} paymentStatus={} httpStatus={} latencyMs={}",
                    paymentId,
                    response.getStatus(),
                    paymentSummary.getStatus(),
                    HttpStatus.OK.value(),
                    toLatencyMillis(startNanos)
            );
            return ResponseEntity.ok(response);
        } catch (PaymentNotFoundException ex) {
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage(ex.getMessage());
            log.warn(
                    "payment-status request_end paymentId={} status={} httpStatus={} latencyMs={} reason={}",
                    paymentId,
                    response.getStatus(),
                    HttpStatus.NOT_FOUND.value(),
                    toLatencyMillis(startNanos),
                    ex.getMessage()
            );
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
    }

    @PostMapping("/{paymentId}/cancel")
    public ResponseEntity<PaymentCancelResponse> cancelPayment(@PathVariable UUID paymentId) {
        long startNanos = System.nanoTime();
        log.info("payment-cancel request_start paymentId={}", paymentId);
        PaymentCancelResponse response = new PaymentCancelResponse();

        try {
            PaymentCancellation paymentCancellation = paymentService.cancelPayment(paymentId);
            response.setStatus(ResponseStatus.SUCCESS);
            response.setPaymentCancellation(paymentCancellation);
            response.setMessage("Payment cancelled successfully");
            log.info(
                    "payment-cancel request_end paymentId={} status={} paymentStatus={} lockReleased={} httpStatus={} latencyMs={}",
                    paymentId,
                    response.getStatus(),
                    paymentCancellation.getStatus(),
                    paymentCancellation.getLockReleased(),
                    HttpStatus.OK.value(),
                    toLatencyMillis(startNanos)
            );
            return ResponseEntity.ok(response);
        } catch (PaymentNotFoundException ex) {
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage(ex.getMessage());
            log.warn(
                    "payment-cancel request_end paymentId={} status={} httpStatus={} latencyMs={} reason={}",
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
