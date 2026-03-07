package com.example.payment_service.dto.common;

import lombok.Data;

@Data
public class ApiResponse {
    private ResponseStatus responseStatus;
    private String message;
}
