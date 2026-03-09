package com.example.payment_service.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class InternalApiAuthenticationFilter extends OncePerRequestFilter {
    private static final String INTERNAL_AUTH_HEADER = "X-Internal-Auth";

    private final String sharedSecret;

    public InternalApiAuthenticationFilter(@Value("${internal.api.shared-secret}") String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String providedSecret = request.getHeader(INTERNAL_AUTH_HEADER);
        if (providedSecret == null || providedSecret.isBlank()) {
            writeUnauthorizedResponse(response, "Missing X-Internal-Auth header");
            return;
        }

        if (!sharedSecret.equals(providedSecret)) {
            writeUnauthorizedResponse(response, "Invalid internal auth secret");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":\"FAILURE\",\"message\":\"" + message + "\"}");
    }
}
