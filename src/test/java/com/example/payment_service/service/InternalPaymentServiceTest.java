package com.example.payment_service.service;

import com.example.payment_service.dto.WebhookResponse;
import com.example.payment_service.exceptions.DuplicatePaymentFoundException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.exceptions.PaymentVerificationFailedException;
import com.example.payment_service.gateway.PaymentGateway;
import com.example.payment_service.gateway.PaymentGatewayRegistry;
import com.example.payment_service.gateway.model.PaymentWebhookNotification;
import com.example.payment_service.integration.BookingOutcomeGateway;
import com.example.payment_service.model.Payment;
import com.example.payment_service.model.PaymentProvider;
import com.example.payment_service.model.PaymentStatus;
import com.example.payment_service.model.ProcessedWebhook;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.repository.ProcessedWebhookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalPaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ProcessedWebhookRepository processedWebhookRepository;

    @Mock
    private PaymentGatewayRegistry paymentGatewayRegistry;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private BookingOutcomeGateway bookingOutcomeGateway;

    private InternalPaymentService internalPaymentService;

    @BeforeEach
    void setUp() {
        internalPaymentService = new InternalPaymentService(
                paymentRepository,
                processedWebhookRepository,
                paymentGatewayRegistry,
                bookingOutcomeGateway
        );
    }

    @Test
    void handleWebhookMarksPaymentSuccessfulAndRecordsWebhook() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = buildPayment(paymentId, PaymentStatus.PENDING);
        when(paymentGatewayRegistry.get(PaymentProvider.RAZORPAY)).thenReturn(paymentGateway);
        when(paymentGateway.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(paymentGateway.parseWebhook(any())).thenReturn(buildNotification(paymentId));

        when(processedWebhookRepository.findByProviderAndProviderEventId("RAZORPAY", "payment.captured:pay_123:captured"))
                .thenReturn(Optional.empty());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(processedWebhookRepository.save(any(ProcessedWebhook.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WebhookResponse response = internalPaymentService.handleWebhook(PaymentProvider.RAZORPAY, validWebhookBody(paymentId), "signature");

        assertEquals("SUCCESS", response.getStatus().name());
        assertEquals(paymentId.toString(), response.getPaymentId());
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertTrue(Boolean.TRUE.equals(response.getBookingConfirmationTriggered()));
        verify(paymentRepository).save(payment);
        verify(processedWebhookRepository).save(any(ProcessedWebhook.class));
    }

    @Test
    void handleWebhookRejectsDuplicateWebhook() {
        when(paymentGatewayRegistry.get(PaymentProvider.RAZORPAY)).thenReturn(paymentGateway);
        when(paymentGateway.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(paymentGateway.parseWebhook(any())).thenReturn(buildNotification(UUID.randomUUID()));

        when(processedWebhookRepository.findByProviderAndProviderEventId("RAZORPAY", "payment.captured:pay_123:captured"))
                .thenReturn(Optional.of(new ProcessedWebhook()));

        assertThrows(DuplicatePaymentFoundException.class, () -> internalPaymentService.handleWebhook(PaymentProvider.RAZORPAY, validWebhookBody(UUID.randomUUID()), "signature"));
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void handleWebhookRejectsInvalidSignature() {
        when(paymentGatewayRegistry.get(PaymentProvider.RAZORPAY)).thenReturn(paymentGateway);
        when(paymentGateway.verifyWebhookSignature(any(), any())).thenReturn(false);

        assertThrows(PaymentVerificationFailedException.class,
                () -> internalPaymentService.handleWebhook(PaymentProvider.RAZORPAY, validWebhookBody(UUID.randomUUID()), "bad-signature"));
    }

    @Test
    void reconcilePaymentReturnsFailureForIneligibleStatus() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = buildPayment(paymentId, PaymentStatus.FAILED);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        ReconcilePaymentResult response = internalPaymentService.reconcilePayment(paymentId);

        assertFalse(response.isSuccess());
        assertEquals(PaymentStatus.FAILED, response.getPaymentStatus());
        assertFalse(response.isRefunded());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void reconcilePaymentKeepsSuccessfulPaymentWhenConfirmationSucceeds() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = buildPayment(paymentId, PaymentStatus.SUCCESS);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReconcilePaymentResult response = internalPaymentService.reconcilePayment(paymentId);

        assertTrue(response.isSuccess());
        assertEquals(PaymentStatus.SUCCESS, response.getPaymentStatus());
        assertTrue(response.isBookingConfirmationTriggered());
        assertFalse(response.isRefunded());
        verify(paymentRepository).save(payment);
    }

    @Test
    void reconcilePaymentRefundsWhenConfirmationFails() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = buildPayment(paymentId, PaymentStatus.SUCCESS_CONFIRMATION_FAILED);
        payment.setFailureReason("downstream booking failed");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new IllegalStateException("downstream booking failed"))
                .doNothing()
                .when(bookingOutcomeGateway).notifyPaymentOutcome(payment);

        ReconcilePaymentResult response = internalPaymentService.reconcilePayment(paymentId);

        assertFalse(response.isSuccess());
        assertEquals(PaymentStatus.REFUNDED, response.getPaymentStatus());
        assertTrue(response.isRefunded());
        assertTrue(response.isLockReleased());
        verify(paymentRepository).save(payment);
    }

    @Test
    void reconcilePaymentThrowsWhenPaymentDoesNotExist() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        assertThrows(PaymentNotFoundException.class, () -> internalPaymentService.reconcilePayment(paymentId));
    }

    private String validWebhookBody(UUID paymentId) {
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
                """.formatted(paymentId);
    }

    private PaymentWebhookNotification buildNotification(UUID paymentId) {
        return PaymentWebhookNotification.builder()
                .provider(PaymentProvider.RAZORPAY)
                .eventType("payment.captured")
                .providerEventId("payment.captured:pay_123:captured")
                .providerPaymentId("pay_123")
                .providerOrderId("order_123")
                .providerStatus("captured")
                .notes(java.util.Map.of("paymentId", paymentId.toString()))
                .build();
    }

    private Payment buildPayment(UUID paymentId, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId(paymentId);
        payment.setUserId(UUID.randomUUID());
        payment.setEventId(UUID.randomUUID());
        payment.setLockId(UUID.randomUUID());
        payment.setAmountMinor(1000L);
        payment.setCurrency("INR");
        payment.setProvider(PaymentProvider.RAZORPAY);
        payment.setStatus(status);
        return payment;
    }
}
