package com.example.urlshortener.config;

import com.example.urlshortener.auth.CurrentCustomerArgumentResolver;
import com.example.urlshortener.auth.JwtVerifier;
import com.example.urlshortener.auth.SessionAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the session filter to the management API and nothing else.
 *
 * <p>The filter is registered explicitly rather than picked up as a
 * {@code @Component}, because a component-scanned filter is applied to every
 * request in the application -- which would put the public click path behind
 * authentication and break the one thing that must never require a credential.
 * The URL pattern is the whole of the access-control policy and it is one line
 * long on purpose.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Everything under the management API; the click path and actuator are outside it. */
    private static final String MANAGEMENT_API = "/api/v1/*";

    @Bean
    public FilterRegistrationBean<SessionAuthenticationFilter> sessionAuthenticationFilter(
            JwtVerifier verifier, ObjectMapper objectMapper) {

        FilterRegistrationBean<SessionAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SessionAuthenticationFilter(verifier, objectMapper));
        registration.addUrlPatterns(MANAGEMENT_API);
        registration.setOrder(FilterRegistrationBean.LOWEST_PRECEDENCE);
        return registration;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentCustomerArgumentResolver());
    }
}
