package com.dmc.backend.config;

import jakarta.validation.Validator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.mapping.event.ValidatingEntityCallback;

/** Apply Jakarta constraints on MongoDB save/insert, including nested report state invariants. */
@Configuration
public class HazardReportPersistenceConfiguration {
    @Bean
    ValidatingEntityCallback validatingEntityCallback(Validator validator) {
        return new ValidatingEntityCallback(validator);
    }
}
