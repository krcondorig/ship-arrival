package com.shiparrival.config;

import com.shiparrival.adapter.hapaglloyd.HapagLloydTrackingClient;
import com.shiparrival.adapter.hapaglloyd.HapagLloydTrackingException;
import com.shiparrival.adapter.hapaglloyd.HapagLloydTrackingSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HapagLloydFallbackTrackingClientTest {

    private static final String TRACKING_NUMBER = "HLCUNG12607EKZE0";

    private final HapagLloydTrackingClient primary = mock(HapagLloydTrackingClient.class);
    private final HapagLloydTrackingClient fallback = mock(HapagLloydTrackingClient.class);
    private final TrackingFallbackAlerter alerter = mock(TrackingFallbackAlerter.class);
    private final HapagLloydFallbackTrackingClient client =
            new HapagLloydFallbackTrackingClient(primary, fallback, alerter);

    private static HapagLloydTrackingSnapshot snapshot(String source) {
        return HapagLloydTrackingSnapshot.builder()
                .trackingNumber(source)
                .containers(List.of())
                .build();
    }

    @Test
    @DisplayName("Si el primario responde, devuelve su snapshot y nunca toca el respaldo")
    void primarySuccessDoesNotTouchFallback() {
        HapagLloydTrackingSnapshot chromeSnapshot = snapshot("chrome");
        when(primary.fetchTrackingSnapshot(TRACKING_NUMBER)).thenReturn(chromeSnapshot);

        HapagLloydTrackingSnapshot result = client.fetchTrackingSnapshot(TRACKING_NUMBER);

        assertThat(result).isSameAs(chromeSnapshot);
        verify(fallback, never()).fetchTrackingSnapshot(anyString());
        verifyNoInteractions(alerter);
    }

    @Test
    @DisplayName("Si el primario lanza, el compuesto devuelve el snapshot del respaldo")
    void primaryFailureFallsBackToZenRows() {
        when(primary.fetchTrackingSnapshot(TRACKING_NUMBER))
                .thenThrow(new HapagLloydTrackingException("Chrome no arrancó"));
        HapagLloydTrackingSnapshot zenRowsSnapshot = snapshot("zenrows");
        when(fallback.fetchTrackingSnapshot(TRACKING_NUMBER)).thenReturn(zenRowsSnapshot);

        HapagLloydTrackingSnapshot result = client.fetchTrackingSnapshot(TRACKING_NUMBER);

        assertThat(result).isSameAs(zenRowsSnapshot);
        verify(primary, times(1)).fetchTrackingSnapshot(TRACKING_NUMBER);
        verify(fallback, times(1)).fetchTrackingSnapshot(TRACKING_NUMBER);
    }

    @Test
    @DisplayName("Un RuntimeException inesperado del primario también dispara el respaldo")
    void unexpectedRuntimeExceptionAlsoFallsBack() {
        when(primary.fetchTrackingSnapshot(TRACKING_NUMBER))
                .thenThrow(new IllegalStateException("contexto CDP roto"));
        HapagLloydTrackingSnapshot zenRowsSnapshot = snapshot("zenrows");
        when(fallback.fetchTrackingSnapshot(TRACKING_NUMBER)).thenReturn(zenRowsSnapshot);

        assertThat(client.fetchTrackingSnapshot(TRACKING_NUMBER)).isSameAs(zenRowsSnapshot);
    }

    @Test
    @DisplayName("Si ambos fallan, la excepción menciona los dos motivos")
    void bothFailuresMentionBothReasons() {
        when(primary.fetchTrackingSnapshot(TRACKING_NUMBER))
                .thenThrow(new HapagLloydTrackingException("Chrome: WARP sin exit node"));
        when(fallback.fetchTrackingSnapshot(TRACKING_NUMBER))
                .thenThrow(new HapagLloydTrackingException("ZenRows: 402 sin créditos"));

        assertThatThrownBy(() -> client.fetchTrackingSnapshot(TRACKING_NUMBER))
                .isInstanceOf(HapagLloydTrackingException.class)
                .hasMessageContaining("WARP sin exit node")
                .hasMessageContaining("402 sin créditos")
                .hasMessageContaining(TRACKING_NUMBER);
    }

    @Test
    @DisplayName("El alerter se invoca una sola vez por respaldo y lleva el número de guía")
    void alerterWarnsOnceWithTrackingNumber() {
        when(primary.fetchTrackingSnapshot(TRACKING_NUMBER))
                .thenThrow(new HapagLloydTrackingException("Chrome no respondió"));
        when(fallback.fetchTrackingSnapshot(TRACKING_NUMBER)).thenReturn(snapshot("zenrows"));

        client.fetchTrackingSnapshot(TRACKING_NUMBER);

        verify(alerter, times(1)).warnFallback(org.mockito.ArgumentMatchers.eq(TRACKING_NUMBER),
                org.mockito.ArgumentMatchers.any(Throwable.class));
    }

    @Test
    @DisplayName("Si el respaldo también falla, el alerter se invoca igual una sola vez")
    void alerterWarnsOnceWhenBothFail() {
        when(primary.fetchTrackingSnapshot(TRACKING_NUMBER))
                .thenThrow(new HapagLloydTrackingException("Chrome no respondió"));
        when(fallback.fetchTrackingSnapshot(TRACKING_NUMBER))
                .thenThrow(new HapagLloydTrackingException("ZenRows caído"));

        assertThatThrownBy(() -> client.fetchTrackingSnapshot(TRACKING_NUMBER))
                .isInstanceOf(HapagLloydTrackingException.class);

        verify(alerter, times(1)).warnFallback(org.mockito.ArgumentMatchers.eq(TRACKING_NUMBER),
                org.mockito.ArgumentMatchers.any(Throwable.class));
    }

    @Test
    @DisplayName("close() no lanza aunque falle uno, y cierra el otro igualmente")
    void closeNeverThrowsAndClosesBoth() {
        doThrow(new IllegalStateException("fallo al cerrar Chrome")).when(primary).close();

        client.close();

        verify(primary, times(1)).close();
        verify(fallback, times(1)).close();
    }

    @Test
    @DisplayName("close() cierra el primario aunque sea el respaldo el que falla")
    void closeClosesPrimaryWhenFallbackFails() {
        doThrow(new IllegalStateException("fallo al cerrar ZenRows")).when(fallback).close();

        client.close();

        verify(primary, times(1)).close();
        verify(fallback, times(1)).close();
    }

    @Test
    @DisplayName("El mensaje del alerter incluye la guía y el motivo, en una sola línea")
    void warningMessageIsSingleLineWithGuideAndReason() {
        String message = TrackingFallbackAlerter.formatWarningMessage(
                TRACKING_NUMBER,
                new HapagLloydTrackingException("Chrome murió\ncon salto de línea"));

        assertThat(message)
                .contains(TRACKING_NUMBER)
                .contains("Chrome murió con salto de línea")
                .contains("ZenRows")
                .doesNotContain("\n");
    }
}
