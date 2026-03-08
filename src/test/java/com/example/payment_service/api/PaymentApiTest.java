package com.example.payment_service.api;

import com.example.payment_service.config.PaymentFilterConfig;
import com.example.payment_service.controller.PaymentController;
import com.example.payment_service.filter.PaymentJwtAuthenticationFilter;
import com.example.payment_service.model.PaymentStatus;
import com.example.payment_service.model.PaymentSummary;
import com.example.payment_service.service.JWTService;
import com.example.payment_service.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import({PaymentJwtAuthenticationFilter.class, PaymentFilterConfig.class})
class PaymentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JWTService jwtService;

    @Test
    void requestWithoutBearerTokenIsRejected() throws Exception {
        mockMvc.perform(get("/payments/{paymentId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Missing or invalid Authorization header"));

        verifyNoInteractions(jwtService, paymentService);
    }

    @Test
    void requestWithInvalidTokenIsRejected() throws Exception {
        when(jwtService.validateAndExtractClaims("bad-token")).thenThrow(new IllegalArgumentException("bad"));

        mockMvc.perform(get("/payments/{paymentId}", UUID.randomUUID())
                        .header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Invalid JWT token"));

        verifyNoInteractions(paymentService);
    }

    @Test
    void requestWithValidTokenReachesController() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSummary paymentSummary = new PaymentSummary();
        paymentSummary.setPaymentId(paymentId);
        paymentSummary.setStatus(PaymentStatus.CREATED);

        when(jwtService.validateAndExtractClaims("good-token")).thenReturn(Map.of("sub", "user-1"));
        when(paymentService.getPaymentStatus(paymentId)).thenReturn(paymentSummary);

        mockMvc.perform(get("/payments/{paymentId}", paymentId)
                        .header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.payment.paymentId").value(paymentId.toString()));
    }
}
