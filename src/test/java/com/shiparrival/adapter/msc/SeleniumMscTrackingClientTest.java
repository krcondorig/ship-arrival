package com.shiparrival.adapter.msc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.chrome.ChromeOptions;

import static org.assertj.core.api.Assertions.assertThat;

class SeleniumMscTrackingClientTest {

    @Test
    @DisplayName("Debe usar carga EAGER para no esperar recursos secundarios del portal")
    void shouldUseEagerPageLoadStrategy() {
        ChromeOptions options = SeleniumMscTrackingClient.createChromeOptions();

        assertThat(options.asMap()).containsKey("pageLoadStrategy");
        assertThat(options.asMap().get("pageLoadStrategy")).isEqualTo(PageLoadStrategy.EAGER);
    }
}
