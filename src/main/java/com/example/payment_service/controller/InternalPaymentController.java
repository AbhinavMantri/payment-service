package com.example.payment_service.controller;

import com.example.payment_service.dto.PaymentReconcileResponse;
import com.example.payment_service.dto.WebhookRequest;
import com.example.payment_service.dto.WebhookResponse;
import com.example.payment_service.dto.common.ResponseStatus;
import com.example.payment_service.exceptions.DuplicatePaymentFoundException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.service.InternalPaymentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/payments")
public class InternalPaymentController {
    private final InternalPaymentService internalPaymentService;

    @Autowired
    public InternalPaymentController(InternalPaymentService internalPaymentService) {
        this.internalPaymentService = internalPaymentService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<WebhookResponse> handleWebhook(@RequestBody @Valid WebhookRequest request) {
        WebhookResponse response = internalPaymentService.handleWebhook(request);
        try {
            response = internalPaymentService.handleWebhook(request);
            return ResponseEntity.ok(response);
        } catch (PaymentNotFoundException ex) {
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage(ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (DuplicatePaymentFoundException ex) {
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage(ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
    }

    @PostMapping("/{paymentId}/reconcile")
    public ResponseEntity<PaymentReconcileResponse> reconcilePayment(@PathVariable UUID paymentId) {
        try {
            PaymentReconcileResponse response = internalPaymentService.reconcilePayment(paymentId);
            HttpStatus httpStatus = response.getStatus() == ResponseStatus.SUCCESS ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(httpStatus).body(response);
        } catch (PaymentNotFoundException ex) {
            PaymentReconcileResponse response = new PaymentReconcileResponse();
            response.setStatus(ResponseStatus.FAILURE);
            response.setMessage(ex.getMessage());
            response.setPaymentId(paymentId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
    }
}
