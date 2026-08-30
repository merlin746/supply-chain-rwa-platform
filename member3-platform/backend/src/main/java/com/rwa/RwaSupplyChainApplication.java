package com.rwa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class RwaSupplyChainApplication {
    public static void main(String[] args) {
        SpringApplication.run(RwaSupplyChainApplication.class, args);
    }
}
