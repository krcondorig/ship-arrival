package com.shiparrival.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Avance de una ejecución de monitoreo.
 *
 * <p>El dashboard no ejecuta el rastreo: lo dispara en GitHub Actions y lee esta
 * tabla para dibujar la barra de avance. Por eso el progreso tiene que vivir en
 * la base compartida y no en memoria del proceso.</p>
 */
@Entity
@Table(name = "tracking_run_progress")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrackingRunProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TrackingRunStatus status;

    @Builder.Default
    private int total = 0;

    @Builder.Default
    private int processed = 0;

    @Builder.Default
    private int updated = 0;

    @Builder.Default
    private int failed = 0;

    @Column(length = 32)
    private String origin;

    @Column(length = 512)
    private String message;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
