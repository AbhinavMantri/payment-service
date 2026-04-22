package com.example.payment_service.gateway;

import com.example.payment_service.model.PaymentProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentGatewayRegistry {
    private final Map<PaymentProvider, PaymentGateway> gateways;

    public PaymentGatewayRegistry(List<PaymentGateway> gateways) {
        this.gateways = new EnumMap<>(PaymentProvider.class);
        for (PaymentGateway gateway : gateways) {
            this.gateways.put(gateway.provider(), gateway);
        }
    }

    public PaymentGateway get(PaymentProvider provider) {
        PaymentGateway gateway = gateways.get(provider);
        if (gateway == null) {
            throw new IllegalArgumentException("Unsupported payment provider: " + provider);
        }
        return gateway;
    }
}
