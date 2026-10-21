package com.shiparrival;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ShipArrivalApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShipArrivalApplication.class, args);
    }
}
