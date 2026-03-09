package com.example.payment_service.service;

import com.example.payment_service.dto.InitiatePaymentRequest;
import com.example.payment_service.dto.PaymentCancellation;
import com.example.payment_service.dto.PaymentVerificationRequest;
import com.example.payment_service.exceptions.PaymentIdempotencyAlreadyUsedException;
import com.example.payment_service.exceptions.PaymentNotFoundException;
import com.example.payment_service.exceptions.PaymentVerificationFailedException;
import com.example.payment_service.gateway.PaymentGateway;
import com.example.payment_service.gateway.PaymentGatewayRegistry;
import com.example.payment_service.gateway.model.CreatePaymentOrderRequest;
import com.example.payment_service.gateway.model.PaymentGatewayOrder;
import com.example.payment_service.model.Payment;
import com.example.payment_service.model.PaymentIdempotency;
import com.example.payment_service.model.PaymentStatus;
import com.example.payment_service.model.PaymentSummary;
import com.example.payment_service.repository.PaymentIdempotencyRepository;
import com.example.payment_service.repository.PaymentRepository;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class PaymentService {
    private static final UUID DEFAULT_USER_ID =
            UUID.nameUUIDFromBytes("payment-service-default-user".getBytes(StandardCharsets.UTF_8));

    private final PaymentIdempotencyRepository paymentIdempotencyRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGatewayRegistry paymentGatewayRegistry;

    @Autowired
    public PaymentService(
            PaymentIdempotencyRepository paymentIdempotencyRepository,
            PaymentRepository paymentRepository,
            PaymentGatewayRegistry paymentGatewayRegistry
    ) {
        this.paymentIdempotencyRepository = paymentIdempotencyRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGatewayRegistry = paymentGatewayRegistry;
    }

    @Transactional
    public PaymentSummary initiatePayment(String idempotencyKey, InitiatePaymentRequest request) throws PaymentIdempotencyAlreadyUsedException, PaymentNotFoundException {
        log.info(
                "payment-initiate service_start idempotencyKey={} eventId={} lockId={} provider={}",
                idempotencyKey,
                request.getEventId(),
                request.getLockId(),
                request.getProvider()
        );
        String requestHash = buildRequestHash(request);
        Optional<PaymentIdempotency> existingIdempotency =
                paymentIdempotencyRepository.findByIdempotencyKey(idempotencyKey);

        if (existingIdempotency.isPresent()) {
            PaymentIdempotency paymentIdempotency = existingIdempotency.get();
            log.info(
                    "payment-initiate idempotency_hit idempotencyKey={} paymentId={}",
                    idempotencyKey,
                    paymentIdempotency.getPaymentId()
            );
            if (!paymentIdempotency.getRequestHash().equals(requestHash)) {
                log.warn("payment-initiate idempotency_conflict idempotencyKey={}", idempotencyKey);
                throw new PaymentIdempotencyAlreadyUsedException("Idempotency key already used for a different request");
            }

            Payment existingPayment = paymentRepository.findById(paymentIdempotency.getPaymentId())
                    .orElseThrow(() -> new PaymentNotFoundException("Stored idempotent payment not found"));
            log.info(
                    "payment-initiate idempotency_return_existing idempotencyKey={} paymentId={} paymentStatus={}",
                    idempotencyKey,
                    existingPayment.getId(),
                    existingPayment.getStatus()
            );
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
        log.info(
                "payment-initiate payment_created paymentId={} eventId={} lockId={} paymentStatus={}",
                savedPayment.getId(),
                savedPayment.getEventId(),
                savedPayment.getLockId(),
                savedPayment.getStatus()
        );

        PaymentGateway paymentGateway = paymentGatewayRegistry.get(savedPayment.getProvider());
        PaymentGatewayOrder gatewayOrder = paymentGateway.createOrder(CreatePaymentOrderRequest.builder()
                .paymentId(savedPayment.getId())
                .amountMinor(savedPayment.getAmountMinor())
                .currency(savedPayment.getCurrency())
                .provider(savedPayment.getProvider())
                .notes(Map.of("paymentId", savedPayment.getId().toString()))
                .build());
        savedPayment.setProviderOrderId(gatewayOrder.getOrderId());
        savedPayment.setStatus(PaymentStatus.PENDING);
        savedPayment = paymentRepository.save(savedPayment);
        log.info(
                "payment-initiate provider_order_created paymentId={} provider={} providerOrderId={} paymentStatus={}",
                savedPayment.getId(),
                savedPayment.getProvider(),
                savedPayment.getProviderOrderId(),
                savedPayment.getStatus()
        );

        PaymentIdempotency paymentIdempotency = new PaymentIdempotency();
        paymentIdempotency.setUserId(DEFAULT_USER_ID);
        paymentIdempotency.setIdempotencyKey(idempotencyKey);
        paymentIdempotency.setRequestHash(requestHash);
        paymentIdempotency.setPaymentId(savedPayment.getId());
        paymentIdempotencyRepository.save(paymentIdempotency);
        log.info(
                "payment-initiate idempotency_saved idempotencyKey={} paymentId={}",
                idempotencyKey,
                savedPayment.getId()
        );

        return to(savedPayment);
    }

    @Transactional
    public PaymentSummary verifyPayment(UUID paymentId, PaymentVerificationRequest request) {
        log.info(
                "payment-verify service_start paymentId={} providerOrderId={} providerPaymentId={}",
                paymentId,
                request.getProviderOrderId(),
                request.getProviderPaymentId()
        );
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for id: " + paymentId));

        if (payment.getProviderOrderId() == null || !payment.getProviderOrderId().equals(request.getProviderOrderId())) {
            throw new PaymentVerificationFailedException("Provider order ID does not match payment");
        }

        PaymentGateway paymentGateway = paymentGatewayRegistry.get(payment.getProvider());
        if (!paymentGateway.verifyPaymentSignature(request)) {
            throw new PaymentVerificationFailedException("Provider payment signature verification failed");
        }

        payment.setProviderPaymentId(request.getProviderPaymentId());
        payment.setStatus(PaymentStatus.PENDING);
        payment = paymentRepository.save(payment);
        log.info(
                "payment-verify service_end paymentId={} paymentStatus={} providerPaymentId={}",
                paymentId,
                payment.getStatus(),
                payment.getProviderPaymentId()
        );
        return to(payment);
    }

    @Transactional(readOnly = true)
    public PaymentSummary getPaymentStatus(UUID paymentId) {
        log.info("payment-status service_start paymentId={}", paymentId);
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for id: " + paymentId));
        log.info(
                "payment-status payment_loaded paymentId={} paymentStatus={} providerOrderId={} providerPaymentId={}",
                paymentId,
                payment.getStatus(),
                payment.getProviderOrderId(),
                payment.getProviderPaymentId()
        );
        return to(payment);
    }

    @Transactional
    public PaymentCancellation cancelPayment(UUID paymentId) {
        log.info("payment-cancel service_start paymentId={}", paymentId);
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for id: " + paymentId));
        log.info("payment-cancel payment_loaded paymentId={} paymentStatus={} lockId={}", paymentId, payment.getStatus(), payment.getLockId());

        if (payment.getStatus() == PaymentStatus.CREATED || payment.getStatus() == PaymentStatus.PENDING) {
            payment.setStatus(PaymentStatus.CANCELLED);
            payment = paymentRepository.save(payment);
            log.info("payment-cancel payment_cancelled paymentId={} paymentStatus={}", paymentId, payment.getStatus());
        } else {
            log.info("payment-cancel payment_not_cancelled paymentId={} paymentStatus={}", paymentId, payment.getStatus());
        }

        PaymentCancellation paymentCancellation = new PaymentCancellation();
        paymentCancellation.setPaymentId(payment.getId());
        paymentCancellation.setStatus(payment.getStatus());
        paymentCancellation.setLockReleased(payment.getStatus() == PaymentStatus.CANCELLED);
        log.info(
                "payment-cancel service_end paymentId={} paymentStatus={} lockReleased={}",
                paymentId,
                paymentCancellation.getStatus(),
                paymentCancellation.getLockReleased()
        );
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
        paymentSummary.setProviderKeyId(paymentGatewayRegistry.get(payment.getProvider()).publicKey());
        paymentSummary.setProviderOrderId(payment.getProviderOrderId());
        paymentSummary.setProviderPaymentId(payment.getProviderPaymentId());

        return paymentSummary;
    }
}
