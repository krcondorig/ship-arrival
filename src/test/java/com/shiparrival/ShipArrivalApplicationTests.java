package com.shiparrival;

import com.shiparrival.service.CarrierTrackingCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class ShipArrivalApplicationTests {

    @Autowired
    private CarrierTrackingCoordinator coordinator;

    @Test
    void contextLoads() {
        assertNotNull(coordinator);
    }
}
