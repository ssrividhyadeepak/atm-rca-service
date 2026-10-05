package com.srividhya.bankrca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BankRcaApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankRcaApplication.class, args);
    }
}
