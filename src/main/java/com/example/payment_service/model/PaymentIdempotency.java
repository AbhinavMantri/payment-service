package com.example.payment_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "payment_idempotency",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_idempotency_user_key", columnNames = {"user_id", "idempotency_key"})
        }
)
@Data
@EqualsAndHashCode(callSuper = true)
public class PaymentIdempotency extends BaseEntity {
    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, length = 128)
    private String requestHash;

    @Column(nullable = false)
    private UUID paymentId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
