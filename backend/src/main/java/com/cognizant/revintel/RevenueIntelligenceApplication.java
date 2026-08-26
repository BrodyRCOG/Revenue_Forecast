package com.cognizant.revintel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RevenueIntelligenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RevenueIntelligenceApplication.class, args);
    }
}
