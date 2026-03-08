package com.example.payment_service.dto;

import com.example.payment_service.dto.common.ApiResponse;
import com.example.payment_service.model.PaymentStatus;
import lombok.Data;

import java.util.UUID;

@Data
public class PaymentReconcileResponse extends ApiResponse {
    private UUID paymentId;
    private PaymentStatus paymentStatus;
    private boolean bookingConfirmationTriggered;
    private boolean refunded;
    private boolean lockReleased;
}
