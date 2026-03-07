package com.example.payment_service.repository;

import com.example.payment_service.model.ProcessedWebhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedWebhookRepository extends JpaRepository<ProcessedWebhook, UUID> {
}
