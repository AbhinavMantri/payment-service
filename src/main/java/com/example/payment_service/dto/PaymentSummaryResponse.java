package com.example.payment_service.dto;

import com.example.payment_service.dto.common.ApiResponse;
import com.example.payment_service.model.PaymentSummary;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PaymentSummaryResponse extends ApiResponse {
    private PaymentSummary payment;
}
