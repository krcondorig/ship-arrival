package com.shiparrival.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "server.servlet.session.tracking-modes=cookie",
        "spring.datasource.url=jdbc:h2:mem:sessionredirect;DB_CLOSE_DELAY=-1"
})
class SessionRedirectIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("El primer POST sin cookie no debe redirigir con ;jsessionid en la URL")
    void firstPostWithoutCookieShouldRedirectToRoot() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        HttpRequest post = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/shipments"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("trackingNumber=TESTREDIRECT1&carrier=ONE"))
                .build();

        HttpResponse<String> response = client.send(post, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location)
                .as("Tomcat no debe escribir ;jsessionid en la URL de redirect")
                .doesNotContain(";jsessionid");

        URI base = URI.create("http://localhost:" + port + "/");
        HttpRequest followUp = HttpRequest.newBuilder()
                .uri(base.resolve(location))
                .GET()
                .build();

        HttpResponse<String> dashboard = client.send(followUp, HttpResponse.BodyHandlers.ofString());

        assertThat(dashboard.statusCode()).isEqualTo(200);
        assertThat(dashboard.body()).contains("TESTREDIRECT1");
    }
}
