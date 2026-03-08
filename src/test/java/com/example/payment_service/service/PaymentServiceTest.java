package com.example.payment_service.service;

import com.example.payment_service.dto.InitiatePaymentRequest;
import com.example.payment_service.dto.PaymentCancellation;
import com.example.payment_service.exceptions.PaymentIdempotencyAlreadyUsedException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.model.Payment;
import com.example.payment_service.model.PaymentIdempotency;
import com.example.payment_service.model.PaymentProvider;
import com.example.payment_service.model.PaymentStatus;
import com.example.payment_service.model.PaymentSummary;
import com.example.payment_service.repository.PaymentIdempotencyRepository;
import com.example.payment_service.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
class PaymentServiceTest {

    @Mock
    private PaymentIdempotencyRepository paymentIdempotencyRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    void initiatePaymentCreatesNewPaymentAndStoresIdempotency() {
        InitiatePaymentRequest request = buildRequest();
        Payment savedPayment = buildPayment(UUID.randomUUID(), PaymentStatus.CREATED);

        when(paymentIdempotencyRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);

        PaymentSummary result = paymentService.initiatePayment("idem-1", request);

        assertEquals(savedPayment.getId(), result.getPaymentId());
        assertEquals(PaymentStatus.CREATED, result.getStatus());
        verify(paymentRepository).save(any(Payment.class));
        verify(paymentIdempotencyRepository).save(any(PaymentIdempotency.class));
    }

    @Test
    void initiatePaymentReturnsExistingPaymentForMatchingIdempotency() {
        InitiatePaymentRequest request = buildRequest();
        UUID paymentId = UUID.randomUUID();
        PaymentIdempotency idempotency = new PaymentIdempotency();
        idempotency.setPaymentId(paymentId);
        idempotency.setRequestHash(request.getEventId() + ":" + request.getLockId() + ":" + request.getProvider());

        Payment existingPayment = buildPayment(paymentId, PaymentStatus.SUCCESS);

        when(paymentIdempotencyRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(idempotency));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(existingPayment));

        PaymentSummary result = paymentService.initiatePayment("idem-1", request);

        assertEquals(paymentId, result.getPaymentId());
        assertEquals(PaymentStatus.SUCCESS, result.getStatus());
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(paymentIdempotencyRepository, never()).save(any(PaymentIdempotency.class));
    }

    @Test
    void initiatePaymentThrowsWhenIdempotencyKeyIsReusedForDifferentRequest() {
        InitiatePaymentRequest request = buildRequest();
        PaymentIdempotency idempotency = new PaymentIdempotency();
        idempotency.setRequestHash("different-request");

        when(paymentIdempotencyRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(idempotency));

        assertThrows(PaymentIdempotencyAlreadyUsedException.class,
                () -> paymentService.initiatePayment("idem-1", request));
    }

    @Test
    void getPaymentStatusThrowsWhenPaymentIsMissing() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        assertThrows(PaymentNotFoundException.class, () -> paymentService.getPaymentStatus(paymentId));
    }

    @Test
    void cancelPaymentMarksCreatedPaymentAsCancelled() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = buildPayment(paymentId, PaymentStatus.CREATED);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentCancellation result = paymentService.cancelPayment(paymentId);

        assertEquals(PaymentStatus.CANCELLED, result.getStatus());
        assertTrue(result.getLockReleased());
        verify(paymentRepository).save(payment);
    }

    @Test
    void cancelPaymentLeavesNonCancellablePaymentUntouched() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = buildPayment(paymentId, PaymentStatus.SUCCESS);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        PaymentCancellation result = paymentService.cancelPayment(paymentId);

        assertEquals(PaymentStatus.SUCCESS, result.getStatus());
        assertFalse(result.getLockReleased());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    private InitiatePaymentRequest buildRequest() {
        InitiatePaymentRequest request = new InitiatePaymentRequest();
        request.setEventId(UUID.randomUUID());
        request.setLockId(UUID.randomUUID());
        request.setProvider(PaymentProvider.RAZORPAY);
        return request;
    }

    private Payment buildPayment(UUID paymentId, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId(paymentId);
        payment.setEventId(UUID.randomUUID());
        payment.setLockId(UUID.randomUUID());
        payment.setAmountMinor(1000L);
        payment.setCurrency("INR");
        payment.setProvider(PaymentProvider.RAZORPAY);
        payment.setStatus(status);
        return payment;
    }
}
