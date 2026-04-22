package com.example.payment_service.dto;

import com.example.payment_service.dto.common.ApiResponse;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PaymentCancelResponse extends ApiResponse {
    private PaymentCancellation paymentCancellation;
}
