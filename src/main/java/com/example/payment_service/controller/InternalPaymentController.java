package com.example.payment_service.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/payments")
public class InternalPaymentController {

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> handleWebhook(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                "message", "Payment webhook placeholder",
                "payload", payload
        ));
    }

    @PostMapping("/{paymentId}/reconcile")
    public ResponseEntity<Map<String, Object>> reconcilePayment(@PathVariable UUID paymentId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                "message", "Reconcile payment placeholder",
                "paymentId", paymentId
        ));
    }
}
