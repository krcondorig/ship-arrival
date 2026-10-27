package com.shiparrival.repository;

import com.shiparrival.domain.TrackingRunProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TrackingRunProgressRepository extends JpaRepository<TrackingRunProgress, Long> {

    Optional<TrackingRunProgress> findByRunId(String runId);

    Optional<TrackingRunProgress> findTopByOrderByStartedAtDesc();
}
