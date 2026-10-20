package com.shiparrival.adapter;

import com.shiparrival.domain.Carrier;

public interface CarrierTrackingAdapter {

    boolean supports(Carrier carrier);

    TrackingResult track(String trackingNumber);
}
