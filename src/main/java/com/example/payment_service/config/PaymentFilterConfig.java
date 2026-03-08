package com.example.payment_service.config;

import com.example.payment_service.filter.PaymentJwtAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class PaymentFilterConfig {

    @Bean
    public FilterRegistrationBean<PaymentJwtAuthenticationFilter> paymentJwtAuthenticationFilterRegistration(
            PaymentJwtAuthenticationFilter paymentJwtAuthenticationFilter
    ) {
        FilterRegistrationBean<PaymentJwtAuthenticationFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(paymentJwtAuthenticationFilter);
        registrationBean.addUrlPatterns("/payments/*");
        registrationBean.setName("paymentJwtAuthenticationFilter");
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registrationBean;
    }
}
