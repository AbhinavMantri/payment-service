package com.example.payment_service.service;

import com.example.payment_service.dto.PaymentReconcileResponse;
import com.example.payment_service.dto.WebhookRequest;
import com.example.payment_service.dto.WebhookResponse;
import com.example.payment_service.exceptions.DuplicatePaymentFoundException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.model.Payment;
import com.example.payment_service.model.PaymentProvider;
import com.example.payment_service.model.PaymentStatus;
import com.example.payment_service.model.ProcessedWebhook;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.repository.ProcessedWebhookRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
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

    @InjectMocks
    private InternalPaymentService internalPaymentService;

    @Test
    void handleWebhookMarksPaymentSuccessfulAndRecordsWebhook() {
        UUID paymentId = UUID.randomUUID();
        WebhookRequest request = buildWebhookRequest(paymentId, "payment.captured", "captured");
        Payment payment = buildPayment(paymentId, PaymentStatus.PENDING);

        when(processedWebhookRepository.findByProviderAndProviderEventId("RAZORPAY", "payment.captured:pay_123:captured"))
                .thenReturn(Optional.empty());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(processedWebhookRepository.save(any(ProcessedWebhook.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WebhookResponse response = internalPaymentService.handleWebhook(request);

        assertEquals("SUCCESS", response.getStatus().name());
        assertEquals(paymentId.toString(), response.getPaymentId());
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertTrue(Boolean.TRUE.equals(response.getBookingConfirmationTriggered()));
        verify(paymentRepository).save(payment);
        verify(processedWebhookRepository).save(any(ProcessedWebhook.class));
    }

    @Test
    void handleWebhookRejectsDuplicateWebhook() {
        WebhookRequest request = buildWebhookRequest(UUID.randomUUID(), "payment.captured", "captured");

        when(processedWebhookRepository.findByProviderAndProviderEventId("RAZORPAY", "payment.captured:pay_123:captured"))
                .thenReturn(Optional.of(new ProcessedWebhook()));

        assertThrows(DuplicatePaymentFoundException.class, () -> internalPaymentService.handleWebhook(request));
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void reconcilePaymentReturnsFailureForIneligibleStatus() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = buildPayment(paymentId, PaymentStatus.FAILED);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        PaymentReconcileResponse response = internalPaymentService.reconcilePayment(paymentId);

        assertEquals("FAILURE", response.getStatus().name());
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

        PaymentReconcileResponse response = internalPaymentService.reconcilePayment(paymentId);

        assertEquals("SUCCESS", response.getStatus().name());
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

        PaymentReconcileResponse response = internalPaymentService.reconcilePayment(paymentId);

        assertEquals("FAILURE", response.getStatus().name());
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

    private WebhookRequest buildWebhookRequest(UUID paymentId, String event, String status) {
        WebhookRequest request = new WebhookRequest();
        request.setEvent(event);

        WebhookRequest.Entity entity = new WebhookRequest.Entity();
        entity.setId("pay_123");
        entity.setOrderId("order_123");
        entity.setStatus(status);
        entity.setNotes(Map.of("paymentId", paymentId.toString()));

        WebhookRequest.Payment payment = new WebhookRequest.Payment();
        payment.setEntity(entity);

        WebhookRequest.Payload payload = new WebhookRequest.Payload();
        payload.setPayment(payment);

        request.setPayload(payload);
        return request;
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
