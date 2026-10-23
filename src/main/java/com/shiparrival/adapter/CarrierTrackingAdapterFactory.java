package com.shiparrival.adapter;

import com.shiparrival.domain.Carrier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class CarrierTrackingAdapterFactory {

    private final List<CarrierTrackingAdapter> adapters;

    public CarrierTrackingAdapterFactory(List<CarrierTrackingAdapter> adapters) {
        this.adapters = adapters;
    }

    public CarrierTrackingAdapter getAdapter(Carrier carrier) {
        return adapters.stream()
                .filter(a -> a.supports(carrier))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No tracking adapter registered for carrier: " + carrier));
    }

    /**
     * Libera los recursos de todos los adaptadores. Se invoca al finalizar cada
     * ciclo de monitoreo para cerrar, por ejemplo, el navegador de MSC.
     */
    public void releaseResources() {
        for (CarrierTrackingAdapter adapter : adapters) {
            try {
                adapter.releaseResources();
            } catch (Exception ex) {
                log.warn("No se pudieron liberar los recursos del adaptador {}: {}",
                        adapter.getClass().getSimpleName(), ex.getMessage());
            }
        }
    }
}
