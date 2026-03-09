package com.example.payment_service.gateway.razorpay;

import com.example.payment_service.dto.PaymentVerificationRequest;
import com.example.payment_service.gateway.CreatePaymentOrderRequest;
import com.example.payment_service.gateway.PaymentGateway;
import com.example.payment_service.gateway.PaymentGatewayOrder;
import com.example.payment_service.gateway.razorpay.model.RazorpayOrderRequest;
import com.example.payment_service.gateway.razorpay.model.RazorpayOrderResponse;
import com.example.payment_service.model.PaymentProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class RazorpayPaymentGateway implements PaymentGateway {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final RazorpayApiClient razorpayApiClient;
    private final String keyId;
    private final byte[] keySecret;
    private final byte[] webhookSecret;

    public RazorpayPaymentGateway(
            RazorpayApiClient razorpayApiClient,
            @Value("${payment.razorpay.key-id}") String keyId,
            @Value("${payment.razorpay.key-secret}") String keySecret,
            @Value("${payment.razorpay.webhook-secret}") String webhookSecret
    ) {
        this.razorpayApiClient = razorpayApiClient;
        this.keyId = keyId;
        this.keySecret = keySecret.getBytes(StandardCharsets.UTF_8);
        this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.RAZORPAY;
    }

    @Override
    public String publicKey() {
        return keyId;
    }

    @Override
    public PaymentGatewayOrder createOrder(CreatePaymentOrderRequest request) {
        RazorpayOrderResponse response = razorpayApiClient.createOrder(
                RazorpayOrderRequest.builder()
                        .amount(request.getAmountMinor())
                        .currency(request.getCurrency())
                        .receipt(request.getPaymentId().toString())
                        .notes(request.getNotes())
                        .build()
        );
        return PaymentGatewayOrder.builder()
                .orderId(response.getId())
                .publicKey(keyId)
                .amountMinor(response.getAmount())
                .currency(response.getCurrency())
                .build();
    }

    @Override
    public boolean verifyPaymentSignature(PaymentVerificationRequest request) {
        String payload = request.getProviderOrderId() + "|" + request.getProviderPaymentId();
        return verifyHmacSignature(payload, request.getProviderSignature(), keySecret);
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        return verifyHmacSignature(payload, signature, webhookSecret);
    }

    private boolean verifyHmacSignature(String payload, String signature, byte[] secret) {
        byte[] expectedSignature = hmacSha256(payload, secret);
        byte[] providedSignature = hexToBytes(signature);
        return MessageDigest.isEqual(expectedSignature, providedSignature);
    }

    private byte[] hmacSha256(String value, byte[] secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to calculate Razorpay signature", ex);
        }
    }

    private byte[] hexToBytes(String value) {
        if (value == null || value.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            int high = Character.digit(value.charAt(i), 16);
            int low = Character.digit(value.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                return new byte[0];
            }
            result[i / 2] = (byte) ((high << 4) + low);
        }
        return result;
    }
}
