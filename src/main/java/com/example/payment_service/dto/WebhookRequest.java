package com.example.payment_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class WebhookRequest {
    @NotBlank(message = "Event is required")
    private String event;

    private String accountId;
    private Long createdAt;

    @NotNull(message = "Payload is required")
    @Valid
    private Payload payload;

    @JsonProperty("account_id")
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    @JsonProperty("created_at")
    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    @Data
    public static class Payload {
        @NotNull
        @Valid
        private Payment payment;
    }

    @Data
    public static class Payment {
        @NotNull
        @Valid
        private Entity entity;
    }

    @Data
    public static class Entity {
        @NotBlank(message = "Payment ID is required")
        private String id;
        private String orderId;
        private Long amount;
        private String currency;
        @NotBlank(message = "Status is required")
        private String status;
        private String method;
        private String email;
        private String contact;
        private Long createdAt;
        private Map<String, String> notes;

        @JsonProperty("order_id")
        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        @JsonProperty("created_at")
        public void setCreatedAt(Long createdAt) {
            this.createdAt = createdAt;
        }
    }
}
