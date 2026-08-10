package com.example.urlshortener.threat;

import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.repository.ThreatDenylistRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the {@link ThreatCheck} implementation from configuration. */
@Configuration
public class ThreatCheckConfig {

    @Bean
    public ThreatCheck threatCheck(AppProperties properties, ThreatDenylistRepository denylist) {
        return properties.threat().enabled() ? new DenylistThreatCheck(denylist) : new NoOpThreatCheck();
    }
}
