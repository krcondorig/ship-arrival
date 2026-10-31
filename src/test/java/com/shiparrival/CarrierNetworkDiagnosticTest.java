package com.shiparrival;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.adapter.one.OneLineTrackingAdapter;
import com.shiparrival.adapter.msc.MscTrackingAdapter;
import com.shiparrival.adapter.msc.SeleniumMscTrackingClient;
import com.shiparrival.adapter.maersk.MaerskTrackingAdapter;
import com.shiparrival.adapter.maersk.SeleniumMaerskTrackingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real tracking checks for the isolated GitHub carrier network diagnostic. */
@EnabledIfEnvironmentVariable(named = "CARRIER_NETWORK_DIAGNOSTIC", matches = "true")
class CarrierNetworkDiagnosticTest {

    private static final String ONE_GUIDE = "TCLU2987000";
    private static final String MSC_GUIDE = "MSNU7790400";
    private static final String MAERSK_GUIDE = "275792562";

    @Test
    void oneReturnsParsedTrackingData() {
        TrackingResult result = new OneLineTrackingAdapter(
                org.springframework.web.client.RestClient.builder()).track(ONE_GUIDE);
        assertHasTrackingData("ONE", result);
    }

    @Test
    void mscReturnsParsedTrackingData() {
        SeleniumMscTrackingClient client = new SeleniumMscTrackingClient();
        try {
            assertHasTrackingData("MSC", new MscTrackingAdapter(client, new ObjectMapper()).track(MSC_GUIDE));
        } finally {
            client.close();
        }
    }

    @Test
    void maerskReturnsParsedTrackingData() {
        SeleniumMaerskTrackingClient client = new SeleniumMaerskTrackingClient();
        try {
            assertHasTrackingData("MAERSK", new MaerskTrackingAdapter(client, new ObjectMapper()).track(MAERSK_GUIDE));
        } finally {
            client.close();
        }
    }

    private void assertHasTrackingData(String carrier, TrackingResult result) {
        String summary = carrier + " success=" + result.isSuccess()
                + " status=" + result.getDetectedStatus()
                + " containerPresent=" + hasText(result.getContainerNumber())
                + " vesselPresent=" + hasText(result.getVesselName())
                + " etaPresent=" + (result.getEstimatedArrival() != null)
                + " arrivalPresent=" + (result.getActualArrival() != null);
        System.out.println(summary);
        assertThat(result.isSuccess()).as("%s adapter should return tracking data", carrier).isTrue();
        assertThat(hasText(result.getTrackingNumber()) || hasText(result.getContainerNumber())
                        || hasText(result.getVesselName()) || result.getEstimatedArrival() != null
                        || result.getActualArrival() != null)
                .as("%s adapter should return at least one parsed tracking field", carrier).isTrue();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
