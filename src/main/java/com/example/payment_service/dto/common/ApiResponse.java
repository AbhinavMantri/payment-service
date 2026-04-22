package com.example.payment_service.dto.common;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@NoArgsConstructor
@SuperBuilder(toBuilder = true)
public class ApiResponse {
    private ResponseStatus status;
    private String message;
}
