package com.example.payment_service.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JWTServiceTest {
    private static final String SECRET = "test-secret";
    private static final String ISSUER = "user-service";

    private final JWTService jwtService = new JWTService(SECRET, ISSUER, new ObjectMapper());

    @Test
    void validateAndExtractClaimsAcceptsValidToken() throws Exception {
        String token = createToken(Map.of(
                "iss", ISSUER,
                "sub", "user-123",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        ));

        Map<String, Object> claims = jwtService.validateAndExtractClaims(token);

        assertEquals("user-123", claims.get("sub"));
        assertEquals(ISSUER, claims.get("iss"));
    }

    @Test
    void validateAndExtractClaimsRejectsExpiredToken() throws Exception {
        String token = createToken(Map.of(
                "iss", ISSUER,
                "exp", Instant.now().minusSeconds(60).getEpochSecond()
        ));

        assertThrows(IllegalArgumentException.class, () -> jwtService.validateAndExtractClaims(token));
    }

    @Test
    void validateAndExtractClaimsRejectsWrongIssuer() throws Exception {
        String token = createToken(Map.of(
                "iss", "other-service",
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        ));

        assertThrows(IllegalArgumentException.class, () -> jwtService.validateAndExtractClaims(token));
    }

    @Test
    void validateAndExtractClaimsRejectsTamperedSignature() throws Exception {
        String token = createToken(Map.of(
                "iss", ISSUER,
                "exp", Instant.now().plusSeconds(300).getEpochSecond()
        ));
        String tamperedToken = token.substring(0, token.length() - 2) + "ab";

        assertThrows(IllegalArgumentException.class, () -> jwtService.validateAndExtractClaims(tamperedToken));
    }

    private String createToken(Map<String, Object> claims) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String header = base64UrlEncode(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String payload = base64UrlEncode(objectMapper.writeValueAsBytes(claims));
        String unsignedToken = header + "." + payload;
        String signature = base64UrlEncode(sign(unsignedToken));
        return unsignedToken + "." + signature;
    }

    private byte[] sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private String base64UrlEncode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
