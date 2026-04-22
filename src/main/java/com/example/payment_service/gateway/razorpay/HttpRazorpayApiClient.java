package com.example.payment_service.gateway.razorpay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.example.payment_service.gateway.razorpay.model.RazorpayOrderRequest;
import com.example.payment_service.gateway.razorpay.model.RazorpayOrderResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class HttpRazorpayApiClient implements RazorpayApiClient {
    private final RestClient restClient;
    private final String basicAuthHeader;

    public HttpRazorpayApiClient(
            RestClient.Builder restClientBuilder,
            @Value("${payment.razorpay.base-url:https://api.razorpay.com}") String baseUrl,
            @Value("${payment.razorpay.key-id}") String keyId,
            @Value("${payment.razorpay.key-secret}") String keySecret
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        String credentials = keyId + ":" + keySecret;
        this.basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public RazorpayOrderResponse createOrder(RazorpayOrderRequest request) {
        return restClient.post()
                .uri("/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(RazorpayOrderResponse.class);
    }
}
