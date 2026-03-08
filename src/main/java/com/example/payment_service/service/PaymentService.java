package com.example.payment_service.service;

import com.example.payment_service.dto.InitiatePaymentRequest;
import com.example.payment_service.dto.PaymentCancellation;
import com.example.payment_service.exceptions.PaymentIdempotencyAlreadyUsedException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.model.Payment;
import com.example.payment_service.model.PaymentIdempotency;
import com.example.payment_service.model.PaymentStatus;
import com.example.payment_service.model.PaymentSummary;
import com.example.payment_service.repository.PaymentIdempotencyRepository;
import com.example.payment_service.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {
    private static final UUID DEFAULT_USER_ID =
            UUID.nameUUIDFromBytes("payment-service-default-user".getBytes(StandardCharsets.UTF_8));

    private final PaymentIdempotencyRepository paymentIdempotencyRepository;
    private final PaymentRepository paymentRepository;

    @Autowired
    public PaymentService(PaymentIdempotencyRepository paymentIdempotencyRepository, PaymentRepository paymentRepository) {
        this.paymentIdempotencyRepository = paymentIdempotencyRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public PaymentSummary initiatePayment(String idempotencyKey, InitiatePaymentRequest request) throws PaymentIdempotencyAlreadyUsedException, PaymentNotFoundException {
        String requestHash = buildRequestHash(request);
        Optional<PaymentIdempotency> existingIdempotency =
                paymentIdempotencyRepository.findByIdempotencyKey(idempotencyKey);

        if (existingIdempotency.isPresent()) {
            PaymentIdempotency paymentIdempotency = existingIdempotency.get();
            if (!paymentIdempotency.getRequestHash().equals(requestHash)) {
                throw new PaymentIdempotencyAlreadyUsedException("Idempotency key already used for a different request");
            }

            Payment existingPayment = paymentRepository.findById(paymentIdempotency.getPaymentId())
                    .orElseThrow(() -> new PaymentNotFoundException("Stored idempotent payment not found"));
            return to(existingPayment);
        }
        //TODO: fetch lock summary from seat allocation service
        Payment payment = new Payment();
        payment.setUserId(DEFAULT_USER_ID);
        payment.setEventId(request.getEventId());
        payment.setLockId(request.getLockId());
        payment.setAmountMinor(0L);
        payment.setCurrency("INR");
        payment.setProvider(request.getProvider());
        payment.setStatus(PaymentStatus.CREATED);
        Payment savedPayment = paymentRepository.save(payment);

        PaymentIdempotency paymentIdempotency = new PaymentIdempotency();
        paymentIdempotency.setUserId(DEFAULT_USER_ID);
        paymentIdempotency.setIdempotencyKey(idempotencyKey);
        paymentIdempotency.setRequestHash(requestHash);
        paymentIdempotency.setPaymentId(savedPayment.getId());
        paymentIdempotencyRepository.save(paymentIdempotency);

        return to(savedPayment);
    }

    @Transactional(readOnly = true)
    public PaymentSummary getPaymentStatus(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for id: " + paymentId));
        return to(payment);
    }

    @Transactional
    public PaymentCancellation cancelPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for id: " + paymentId));

        if (payment.getStatus() == PaymentStatus.CREATED || payment.getStatus() == PaymentStatus.PENDING) {
            payment.setStatus(PaymentStatus.CANCELLED);
            payment = paymentRepository.save(payment);
        }

        PaymentCancellation paymentCancellation = new PaymentCancellation();
        paymentCancellation.setPaymentId(payment.getId());
        paymentCancellation.setStatus(payment.getStatus());
        paymentCancellation.setLockReleased(payment.getStatus() == PaymentStatus.CANCELLED);
        // TODO: Integrate with lock service to release the lock if payment is cancelled
        return paymentCancellation;
    }

    private String buildRequestHash(InitiatePaymentRequest request) {
        return request.getEventId() + ":" + request.getLockId() + ":" + request.getProvider();
    }

    private PaymentSummary to(Payment payment) {
        PaymentSummary paymentSummary = new PaymentSummary();
        paymentSummary.setPaymentId(payment.getId());
        paymentSummary.setEventId(payment.getEventId());
        paymentSummary.setLockId(payment.getLockId());
        paymentSummary.setAmountMinor(payment.getAmountMinor());
        paymentSummary.setCurrency(payment.getCurrency());
        paymentSummary.setStatus(payment.getStatus());
        paymentSummary.setProviderOrderId(payment.getProviderOrderId());
        paymentSummary.setProviderPaymentId(payment.getProviderPaymentId());

        return paymentSummary;
    }
}
