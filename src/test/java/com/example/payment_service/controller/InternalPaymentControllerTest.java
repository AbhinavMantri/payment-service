package com.example.payment_service.controller;

import com.example.payment_service.dto.WebhookResponse;
import com.example.payment_service.dto.common.ResponseStatus;
import com.example.payment_service.exceptions.DuplicatePaymentFoundException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.exceptions.PaymentVerificationFailedException;
import com.example.payment_service.model.PaymentProvider;
import com.example.payment_service.service.ReconcilePaymentResult;
import com.example.payment_service.service.InternalPaymentService;
import com.example.payment_service.service.JWTService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalPaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class InternalPaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InternalPaymentService internalPaymentService;

    @MockitoBean
    private JWTService jwtService;

    @Test
    void handleWebhookReturnsOkAndInvokesServiceOnce() throws Exception {
        WebhookResponse response = WebhookResponse.builder()
                .status(ResponseStatus.SUCCESS)
                .message("Webhook processed successfully")
                .paymentId(UUID.randomUUID().toString())
                .paymentStatus("SUCCESS")
                .bookingConfirmationTriggered(true)
                .build();

        when(internalPaymentService.handleWebhook(any(), any(), any())).thenReturn(response);

        mockMvc.perform(post("/internal/v1/payments/webhook")
                        .header("X-Razorpay-Signature", "signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWebhookBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"));

        verify(internalPaymentService, times(1)).handleWebhook(any(), any(), any());
    }

    @Test
    void handleWebhookReturnsBadRequestWhenPaymentIsMissing() throws Exception {
        when(internalPaymentService.handleWebhook(any(), any(), any()))
                .thenThrow(new PaymentNotFoundException("missing payment"));

        mockMvc.perform(post("/internal/v1/payments/webhook")
                        .header("X-Razorpay-Signature", "signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWebhookBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("missing payment"));
    }

    @Test
    void handleWebhookReturnsConflictWhenWebhookIsDuplicate() throws Exception {
        when(internalPaymentService.handleWebhook(any(), any(), any()))
                .thenThrow(new DuplicatePaymentFoundException("duplicate webhook"));

        mockMvc.perform(post("/internal/v1/payments/webhook")
                        .header("X-Razorpay-Signature", "signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWebhookBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("duplicate webhook"));
    }

    @Test
    void handleWebhookReturnsUnauthorizedWhenSignatureIsInvalid() throws Exception {
        when(internalPaymentService.handleWebhook(any(), any(), any()))
                .thenThrow(new PaymentVerificationFailedException("Invalid webhook signature"));

        mockMvc.perform(post("/internal/v1/payments/webhook")
                        .header("X-Razorpay-Signature", "bad-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWebhookBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Invalid webhook signature"));
    }

    @Test
    void reconcilePaymentReturnsOkWhenServiceSucceeds() throws Exception {
        UUID paymentId = UUID.randomUUID();
        ReconcilePaymentResult response = ReconcilePaymentResult.builder()
                .paymentId(paymentId)
                .paymentStatus(com.example.payment_service.model.PaymentStatus.SUCCESS)
                .success(true)
                .bookingConfirmationTriggered(true)
                .message("Booking confirmation completed successfully")
                .build();

        when(internalPaymentService.reconcilePayment(paymentId)).thenReturn(response);

        mockMvc.perform(post("/internal/v1/payments/{paymentId}/reconcile", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()));
    }

    @Test
    void reconcilePaymentReturnsNotFoundWhenPaymentIsMissing() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(internalPaymentService.reconcilePayment(paymentId))
                .thenThrow(new PaymentNotFoundException("missing reconcile payment"));

        mockMvc.perform(post("/internal/v1/payments/{paymentId}/reconcile", paymentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("missing reconcile payment"));
    }

    @Test
    void handleWebhookAcceptsGenericProviderHeaders() throws Exception {
        String webhookBody = validWebhookBody();
        WebhookResponse response = WebhookResponse.builder()
                .status(ResponseStatus.SUCCESS)
                .eventType("payment.captured")
                .eventId("pay_123")
                .build();
        when(internalPaymentService.handleWebhook(PaymentProvider.RAZORPAY, webhookBody, "generic-signature"))
                .thenReturn(response);

        mockMvc.perform(post("/internal/v1/payments/webhook")
                        .header("X-Payment-Provider", "razorpay")
                        .header("X-Payment-Signature", "generic-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk());
    }

    private String validWebhookBody() {
        return """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_123",
                        "order_id": "order_123",
                        "status": "captured",
                        "notes": {
                          "paymentId": "%s"
                        }
                      }
                    }
                  }
                }
                """.formatted(UUID.randomUUID());
    }
}
