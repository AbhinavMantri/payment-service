package com.example.payment_service.dto;

import java.util.UUID;

import com.example.payment_service.model.PaymentStatus;

import lombok.Data;

@Data
public class PaymentCancellation {
    private UUID paymentId;
    private PaymentStatus status;
    private Boolean lockReleased;
}
