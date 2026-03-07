package com.example.payment_service.dto;

import com.example.payment_service.dto.common.ApiResponse;

import lombok.Data;

@Data
public class PaymentCancelResponse extends ApiResponse {
    private PaymentCancellation paymentCancellation;
}
