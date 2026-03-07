package com.example.payment_service.controller;

import com.example.payment_service.dto.InitiatePaymentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    @PostMapping("/initiate")
    public ResponseEntity<Map<String, Object>> initiatePayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid InitiatePaymentRequest request
    ) {
        // Placeholder response until the payment orchestration is implemented.
        // TODO: Implement payment initiation logic with idempotency handling.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                "message", "Initiate payment placeholder",
                "idempotencyKey", idempotencyKey,
                "request", request
        ));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<Map<String, Object>> getPaymentStatus(@PathVariable UUID paymentId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                "message", "Get payment status placeholder",
                "paymentId", paymentId
        ));
    }

    @PostMapping("/{paymentId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                "message", "Cancel payment placeholder",
                "paymentId", paymentId
        ));
    }
}
