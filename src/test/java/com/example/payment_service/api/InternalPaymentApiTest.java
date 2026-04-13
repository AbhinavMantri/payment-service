package com.example.payment_service.api;

import com.example.payment_service.config.PaymentFilterConfig;
import com.example.payment_service.controller.InternalPaymentController;
import com.example.payment_service.dto.WebhookResponse;
import com.example.payment_service.dto.common.ResponseStatus;
import com.example.payment_service.filter.InternalApiAuthenticationFilter;
import com.example.payment_service.model.PaymentStatus;
import com.example.payment_service.service.ReconcilePaymentResult;
import com.example.payment_service.service.InternalPaymentService;
import com.example.payment_service.service.JWTService;
import com.example.payment_service.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalPaymentController.class)
@Import({InternalApiAuthenticationFilter.class, PaymentFilterConfig.class})
@TestPropertySource(properties = "internal.api.shared-secret=test-shared-secret")
class InternalPaymentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InternalPaymentService internalPaymentService;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JWTService jwtService;

    @Test
    void webhookRequestWithoutInternalAuthHeaderIsAllowedWhenSignatureIsPresent() throws Exception {
        WebhookResponse response = WebhookResponse.builder()
                .status(ResponseStatus.SUCCESS)
                .message("Webhook processed successfully")
                .paymentId(UUID.randomUUID().toString())
                .paymentStatus("SUCCESS")
                .build();

        when(internalPaymentService.handleWebhook(any(), any(), any())).thenReturn(response);

        mockMvc.perform(post("/internal/v1/payments/webhook")
                        .header("X-Payment-Signature", "signature")
                        .contentType(APPLICATION_JSON)
                        .content(validWebhookBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void webhookRequestWithInvalidInternalAuthHeaderStillUsesWebhookPathWithoutFilterRejection() throws Exception {
        WebhookResponse response = WebhookResponse.builder()
                .status(ResponseStatus.SUCCESS)
                .message("Webhook processed successfully")
                .paymentId(UUID.randomUUID().toString())
                .paymentStatus("SUCCESS")
                .build();

        when(internalPaymentService.handleWebhook(any(), any(), any())).thenReturn(response);

        mockMvc.perform(post("/internal/v1/payments/webhook")
                        .header("X-Internal-Auth", "wrong-secret")
                        .header("X-Payment-Signature", "signature")
                        .contentType(APPLICATION_JSON)
                        .content(validWebhookBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void webhookRequestWithInvalidPayloadReturnsBadRequest() throws Exception {
        when(internalPaymentService.handleWebhook(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid webhook payload"));

        mockMvc.perform(post("/internal/v1/payments/webhook")
                        .header("X-Payment-Signature", "signature")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "payload": {
                                    "payment": {
                                      "entity": {
                                        "id": "",
                                        "status": ""
                                      }
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webhookRequestWithValidPayloadReturnsSuccessBody() throws Exception {
        WebhookResponse response = WebhookResponse.builder()
                .status(ResponseStatus.SUCCESS)
                .message("Webhook processed successfully")
                .paymentId(UUID.randomUUID().toString())
                .paymentStatus("SUCCESS")
                .build();

        when(internalPaymentService.handleWebhook(any(), any(), any())).thenReturn(response);

        mockMvc.perform(post("/internal/v1/payments/webhook")
                        .header("X-Payment-Signature", "signature")
                        .contentType(APPLICATION_JSON)
                        .content(validWebhookBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Webhook processed successfully"));
    }

    @Test
    void webhookRequestWithoutSignatureHeaderReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/internal/v1/payments/webhook")
                        .contentType(APPLICATION_JSON)
                        .content(validWebhookBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing payment signature header"));

        verifyNoInteractions(internalPaymentService);
    }

    @Test
    void reconcileRequestWithoutInternalAuthHeaderIsRejected() throws Exception {
        mockMvc.perform(post("/internal/v1/payments/{paymentId}/reconcile", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Missing X-Internal-Auth header"));

        verifyNoInteractions(internalPaymentService);
    }

    @Test
    void reconcileRequestReturnsExpectedPayload() throws Exception {
        UUID paymentId = UUID.randomUUID();
        ReconcilePaymentResult response = ReconcilePaymentResult.builder()
                .paymentId(paymentId)
                .paymentStatus(PaymentStatus.REFUNDED)
                .success(false)
                .bookingConfirmationTriggered(true)
                .refunded(true)
                .lockReleased(true)
                .message("Booking confirmation failed. Refund triggered and lock released")
                .build();

        when(internalPaymentService.reconcilePayment(paymentId)).thenReturn(response);

        mockMvc.perform(post("/internal/v1/payments/{paymentId}/reconcile", paymentId)
                        .header("X-Internal-Auth", "test-shared-secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.refunded").value(true))
                .andExpect(jsonPath("$.lockReleased").value(true));
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
