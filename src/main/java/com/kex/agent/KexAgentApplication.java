package com.kex.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KexAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(KexAgentApplication.class, args);
    }
}
