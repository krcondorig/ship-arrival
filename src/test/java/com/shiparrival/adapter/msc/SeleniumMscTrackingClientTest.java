package com.shiparrival.adapter.msc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.chrome.ChromeOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeleniumMscTrackingClientTest {

    @Test
    @DisplayName("Debe usar carga EAGER para no esperar recursos secundarios del portal")
    void shouldUseEagerPageLoadStrategy() {
        ChromeOptions options = SeleniumMscTrackingClient.createChromeOptions();

        assertThat(options.asMap()).containsKey("pageLoadStrategy");
        assertThat(options.asMap().get("pageLoadStrategy")).isEqualTo(PageLoadStrategy.EAGER);
    }

    @Test
    @DisplayName("Debe bloquear descarga de imágenes para reducir consumo de red y acelerar consultas")
    void shouldDisableImagesLoading() {
        ChromeOptions options = SeleniumMscTrackingClient.createChromeOptions();

        @SuppressWarnings("unchecked")
        Map<String, Object> chromeOptions = (Map<String, Object>) options.asMap().get("goog:chromeOptions");
        assertThat(chromeOptions).isNotNull();

        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) chromeOptions.get("args");
        assertThat(args).contains("--blink-settings=imagesEnabled=false");

        @SuppressWarnings("unchecked")
        Map<String, Object> prefs = (Map<String, Object>) chromeOptions.get("prefs");
        assertThat(prefs).isNotNull();
        assertThat(prefs).containsEntry("profile.managed_default_content_settings.images", 2);
    }
}

