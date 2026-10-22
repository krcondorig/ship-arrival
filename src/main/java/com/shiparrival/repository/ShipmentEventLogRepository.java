package com.shiparrival.repository;

import com.shiparrival.domain.ShipmentEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShipmentEventLogRepository extends JpaRepository<ShipmentEventLog, Long> {

    List<ShipmentEventLog> findByShipmentIdOrderByRecordedAtDesc(Long shipmentId);
    
    void deleteByShipmentId(Long shipmentId);
}
