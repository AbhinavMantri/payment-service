package com.example.payment_service.gateway.razorpay.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class RazorpayOrderRequest {
    Long amount;
    String currency;
    String receipt;
    Map<String, String> notes;
}
