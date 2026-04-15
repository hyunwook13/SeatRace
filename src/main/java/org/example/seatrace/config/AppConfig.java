package org.example.seatrace.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConfigurationPropertiesScan
@EnableScheduling
@EnableJpaAuditing
public class AppConfig {
}
