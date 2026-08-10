package com.example.urlshortener.config;

import com.example.urlshortener.link.AliasPolicy;
import com.example.urlshortener.link.ShortCodeGenerator;
import com.example.urlshortener.link.UrlValidator;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The link policy objects, as beans.
 *
 * <p>They are declared here rather than annotated as components because they are
 * part of the frozen contract: keeping them free of Spring annotations means they
 * can be constructed directly, which is what lets the alphabet, the reserved list
 * and the host rules be exercised without a container.
 */
@Configuration
public class LinkPolicyConfig {

    @Bean
    public ShortCodeGenerator shortCodeGenerator() {
        return new ShortCodeGenerator();
    }

    @Bean
    public AliasPolicy aliasPolicy() {
        return new AliasPolicy();
    }

    /**
     * Refuses our own public host as a target, so a short link cannot point at a
     * short link and build a redirect loop through us.
     */
    @Bean
    public UrlValidator urlValidator(AppProperties properties) {
        return new UrlValidator(URI.create(properties.baseUrl()).getHost());
    }
}
