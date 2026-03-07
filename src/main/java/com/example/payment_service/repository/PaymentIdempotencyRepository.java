package com.example.payment_service.repository;

import com.example.payment_service.model.PaymentIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentIdempotencyRepository extends JpaRepository<PaymentIdempotency, UUID> {
    Optional<PaymentIdempotency> findByIdempotencyKey(String idempotencyKey);
}
