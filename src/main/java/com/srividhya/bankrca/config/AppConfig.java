package com.srividhya.bankrca.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** Fixed at the stub anchor when one is configured (tests), otherwise the real clock. */
    @Bean
    Clock clock(RcaProperties props) {
        Instant anchor = props.stub() == null ? null : props.stub().anchor();
        return anchor == null ? Clock.systemUTC() : Clock.fixed(anchor, ZoneOffset.UTC);
    }
}
