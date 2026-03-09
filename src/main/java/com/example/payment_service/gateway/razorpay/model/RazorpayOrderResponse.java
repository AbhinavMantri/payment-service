package com.example.payment_service.gateway.razorpay.model;

import lombok.Data;

@Data
public class RazorpayOrderResponse {
    private String id;
    private Long amount;
    private String currency;
}
