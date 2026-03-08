package com.example.payment_service.controller;

import com.example.payment_service.dto.PaymentCancellation;
import com.example.payment_service.exceptions.PaymentIdempotencyAlreadyUsedException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.model.PaymentProvider;
import com.example.payment_service.model.PaymentStatus;
import com.example.payment_service.model.PaymentSummary;
import com.example.payment_service.service.JWTService;
import com.example.payment_service.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JWTService jwtService;

    @Test
    void initiatePaymentReturnsCreatedResponse() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSummary paymentSummary = buildPaymentSummary(paymentId, PaymentStatus.CREATED);

        when(paymentService.initiatePayment(eq("idem-1"), any())).thenReturn(paymentSummary);

        mockMvc.perform(post("/payments/initiate")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "%s",
                                  "lockId": "%s",
                                  "provider": "%s"
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID(), PaymentProvider.RAZORPAY)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.payment.paymentId").value(paymentId.toString()));
    }

    @Test
    void getPaymentStatusReturnsNotFoundWhenServiceThrows() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.getPaymentStatus(paymentId)).thenThrow(new PaymentNotFoundException("missing"));

        mockMvc.perform(get("/payments/{paymentId}", paymentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("missing"));
    }

    @Test
    void cancelPaymentReturnsConflictPayloadWhenInitiateHasIdempotencyConflict() throws Exception {
        when(paymentService.initiatePayment(eq("idem-1"), any()))
                .thenThrow(new PaymentIdempotencyAlreadyUsedException("duplicate"));

        mockMvc.perform(post("/payments/initiate")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "%s",
                                  "lockId": "%s",
                                  "provider": "%s"
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID(), PaymentProvider.RAZORPAY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("duplicate"));
    }

    @Test
    void cancelPaymentReturnsCancellationResponse() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentCancellation paymentCancellation = new PaymentCancellation();
        paymentCancellation.setPaymentId(paymentId);
        paymentCancellation.setStatus(PaymentStatus.CANCELLED);
        paymentCancellation.setLockReleased(true);

        when(paymentService.cancelPayment(paymentId)).thenReturn(paymentCancellation);

        mockMvc.perform(post("/payments/{paymentId}/cancel", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.paymentCancellation.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.paymentCancellation.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentCancellation.lockReleased").value(true));
    }

    private PaymentSummary buildPaymentSummary(UUID paymentId, PaymentStatus status) {
        PaymentSummary paymentSummary = new PaymentSummary();
        paymentSummary.setPaymentId(paymentId);
        paymentSummary.setEventId(UUID.randomUUID());
        paymentSummary.setLockId(UUID.randomUUID());
        paymentSummary.setAmountMinor(1000L);
        paymentSummary.setCurrency("INR");
        paymentSummary.setStatus(status);
        return paymentSummary;
    }
}
