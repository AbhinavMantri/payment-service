package com.example.payment_service.service;

import com.example.payment_service.dto.InitiatePaymentRequest;
import com.example.payment_service.dto.PaymentCancellation;
import com.example.payment_service.dto.PaymentVerificationRequest;
import com.example.payment_service.exceptions.PaymentIdempotencyAlreadyUsedException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.exceptions.PaymentVerificationFailedException;
import com.example.payment_service.gateway.PaymentGateway;
import com.example.payment_service.gateway.PaymentGatewayOrder;
import com.example.payment_service.gateway.PaymentGatewayRegistry;
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

    @Mock
    private PaymentGatewayRegistry paymentGatewayRegistry;

    @Mock
    private PaymentGateway paymentGateway;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    void initiatePaymentCreatesNewPaymentAndStoresIdempotency() {
        InitiatePaymentRequest request = buildRequest();
        Payment createdPayment = buildPayment(UUID.randomUUID(), PaymentStatus.CREATED);
        Payment savedPayment = buildPayment(createdPayment.getId(), PaymentStatus.PENDING);
        savedPayment.setProviderOrderId("order_123");

        when(paymentIdempotencyRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(paymentGatewayRegistry.get(PaymentProvider.RAZORPAY)).thenReturn(paymentGateway);
        when(paymentGateway.createOrder(any())).thenReturn(PaymentGatewayOrder.builder()
                .orderId("order_123")
                .publicKey("rzp_test_123")
                .amountMinor(createdPayment.getAmountMinor())
                .currency(createdPayment.getCurrency())
                .build());
        when(paymentGateway.publicKey()).thenReturn("rzp_test_123");
        when(paymentRepository.save(any(Payment.class))).thenReturn(createdPayment, savedPayment);

        PaymentSummary result = paymentService.initiatePayment("idem-1", request);

        assertEquals(savedPayment.getId(), result.getPaymentId());
        assertEquals(PaymentStatus.PENDING, result.getStatus());
        assertEquals("order_123", result.getProviderOrderId());
        verify(paymentRepository, org.mockito.Mockito.times(2)).save(any(Payment.class));
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
        when(paymentGatewayRegistry.get(PaymentProvider.RAZORPAY)).thenReturn(paymentGateway);
        when(paymentGateway.publicKey()).thenReturn("rzp_test_123");

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

    @Test
    void verifyPaymentUpdatesProviderPaymentIdWhenSignatureIsValid() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = buildPayment(paymentId, PaymentStatus.PENDING);
        payment.setProviderOrderId("order_123");
        PaymentVerificationRequest request = new PaymentVerificationRequest();
        request.setProviderOrderId("order_123");
        request.setProviderPaymentId("pay_123");
        request.setProviderSignature("signature_123");

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(paymentGatewayRegistry.get(PaymentProvider.RAZORPAY)).thenReturn(paymentGateway);
        when(paymentGateway.verifyPaymentSignature(request)).thenReturn(true);
        when(paymentGateway.publicKey()).thenReturn("rzp_test_123");
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentSummary result = paymentService.verifyPayment(paymentId, request);

        assertEquals("pay_123", result.getProviderPaymentId());
        assertEquals(PaymentStatus.PENDING, result.getStatus());
    }

    @Test
    void verifyPaymentThrowsWhenSignatureIsInvalid() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = buildPayment(paymentId, PaymentStatus.PENDING);
        payment.setProviderOrderId("order_123");
        PaymentVerificationRequest request = new PaymentVerificationRequest();
        request.setProviderOrderId("order_123");
        request.setProviderPaymentId("pay_123");
        request.setProviderSignature("bad");

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(paymentGatewayRegistry.get(PaymentProvider.RAZORPAY)).thenReturn(paymentGateway);
        when(paymentGateway.verifyPaymentSignature(request)).thenReturn(false);

        assertThrows(PaymentVerificationFailedException.class, () -> paymentService.verifyPayment(paymentId, request));
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
