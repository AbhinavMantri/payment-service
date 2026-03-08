package com.example.payment_service.filter;

import com.example.payment_service.service.JWTService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
public class PaymentJwtAuthenticationFilter extends OncePerRequestFilter {
    private static final String BEARER_PREFIX = "Bearer ";

    private final JWTService jwtService;

    @Autowired
    public PaymentJwtAuthenticationFilter(JWTService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorizationHeader = request.getHeader("Authorization");
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            writeUnauthorizedResponse(response, "Missing or invalid Authorization header");
            return;
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            writeUnauthorizedResponse(response, "Missing JWT token");
            return;
        }

        try {
            Map<String, Object> claims = jwtService.validateAndExtractClaims(token);
            request.setAttribute("jwtClaims", claims);
            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException ex) {
            writeUnauthorizedResponse(response, "Invalid JWT token");
        }
    }

    private void writeUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":\"FAILURE\",\"message\":\"" + message + "\"}");
    }
}
