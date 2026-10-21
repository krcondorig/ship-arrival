package com.shiparrival.adapter;

import com.shiparrival.domain.Carrier;
import org.springframework.stereotype.Component;

import java.util.List;

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
}
