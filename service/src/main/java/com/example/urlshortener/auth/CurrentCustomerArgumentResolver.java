package com.example.urlshortener.auth;

import com.example.urlshortener.error.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Hands controllers the verified caller as a method parameter.
 *
 * <p>A controller that declares {@link CurrentCustomer} cannot be reached without
 * one: {@link SessionAuthenticationFilter} has already refused the request
 * otherwise. The unauthorized case below is therefore a guard against a route
 * being added outside the filter's mappings, not a path a caller can reach -- and
 * it fails closed, which is the only safe direction for that mistake.
 */
public class CurrentCustomerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentCustomer.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {

        Object caller = webRequest.getAttribute(
                SessionAuthenticationFilter.CURRENT_CUSTOMER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (caller instanceof CurrentCustomer customer) {
            return customer;
        }
        throw ApiException.unauthorized();
    }
}
