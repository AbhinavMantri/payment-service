package com.example.payment_service.gateway.razorpay;

import com.example.payment_service.gateway.razorpay.model.RazorpayOrderRequest;
import com.example.payment_service.gateway.razorpay.model.RazorpayOrderResponse;

public interface RazorpayApiClient {
    RazorpayOrderResponse createOrder(RazorpayOrderRequest request);
}
