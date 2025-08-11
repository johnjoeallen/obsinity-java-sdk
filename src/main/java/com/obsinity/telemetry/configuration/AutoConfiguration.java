package com.obsinity.telemetry.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.springframework.core.Ordered.HIGHEST_PRECEDENCE;

@Configuration
@AutoConfigureOrder(value = HIGHEST_PRECEDENCE)
@ComponentScan(basePackages = {"com.obsinity.telemetry"})
@RequiredArgsConstructor
public class AutoConfiguration {
	// Empty class just used for component scan for now
}
