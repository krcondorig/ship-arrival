package com.shiparrival.adapter.hapaglloyd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChromeHapagLloydDiagnosticTest {

    @Test
    void pageStateForDiagnosticsRemovesQueryAndFragment() {
        String state = ChromeHapagLloydTrackingClient.pageStateForDiagnostics(
                "Just a moment...",
                "https://www.hapag-lloyd.com/en/track.html?blno=HLCUNG12607EKZE0&token=private#step");

        assertThat(state).contains("title='Just a moment...'")
                .contains("url='https://www.hapag-lloyd.com/en/track.html'")
                .doesNotContain("HLCUNG12607EKZE0")
                .doesNotContain("token=private")
                .doesNotContain("#step");
    }
}
