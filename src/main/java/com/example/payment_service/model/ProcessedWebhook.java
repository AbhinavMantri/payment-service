package com.example.payment_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

@Entity
@Table(name = "processed_webhooks")
@Data
@EqualsAndHashCode(callSuper = true)
public class ProcessedWebhook extends BaseEntity {
    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false, unique = true, length = 128)
    private String providerEventId;

    @Column(nullable = false, updatable = false)
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        this.processedAt = Instant.now();
    }
}
