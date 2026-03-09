package com.example.payment_service.api;

import com.example.payment_service.config.PaymentFilterConfig;
import com.example.payment_service.controller.InternalPaymentController;
import com.example.payment_service.dto.PaymentReconcileResponse;
import com.example.payment_service.dto.WebhookResponse;
import com.example.payment_service.dto.common.ResponseStatus;
import com.example.payment_service.filter.InternalApiAuthenticationFilter;
import com.example.payment_service.model.PaymentStatus;
import com.example.payment_service.service.InternalPaymentService;
import com.example.payment_service.service.JWTService;
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
    private JWTService jwtService;

    @Test
    void webhookRequestWithoutInternalAuthHeaderIsRejected() throws Exception {
        mockMvc.perform(post("/internal/payments/webhook")
                        .contentType(APPLICATION_JSON)
                        .content(validWebhookBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Missing X-Internal-Auth header"));

        verifyNoInteractions(internalPaymentService);
    }

    @Test
    void webhookRequestWithInvalidInternalAuthHeaderIsRejected() throws Exception {
        mockMvc.perform(post("/internal/payments/webhook")
                        .header("X-Internal-Auth", "wrong-secret")
                        .contentType(APPLICATION_JSON)
                        .content(validWebhookBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Invalid internal auth secret"));

        verifyNoInteractions(internalPaymentService);
    }

    @Test
    void webhookRequestWithInvalidPayloadReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/internal/payments/webhook")
                        .header("X-Internal-Auth", "test-shared-secret")
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
        WebhookResponse response = new WebhookResponse();
        response.setStatus(ResponseStatus.SUCCESS);
        response.setMessage("Webhook processed successfully");
        response.setPaymentId(UUID.randomUUID().toString());
        response.setPaymentStatus("SUCCESS");

        when(internalPaymentService.handleWebhook(any())).thenReturn(response);

        mockMvc.perform(post("/internal/payments/webhook")
                        .header("X-Internal-Auth", "test-shared-secret")
                        .contentType(APPLICATION_JSON)
                        .content(validWebhookBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Webhook processed successfully"));
    }

    @Test
    void reconcileRequestReturnsExpectedPayload() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentReconcileResponse response = new PaymentReconcileResponse();
        response.setStatus(ResponseStatus.FAILURE);
        response.setMessage("Booking confirmation failed. Refund triggered and lock released");
        response.setPaymentId(paymentId);
        response.setPaymentStatus(PaymentStatus.REFUNDED);
        response.setRefunded(true);
        response.setLockReleased(true);

        when(internalPaymentService.reconcilePayment(paymentId)).thenReturn(response);

        mockMvc.perform(post("/internal/payments/{paymentId}/reconcile", paymentId)
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
