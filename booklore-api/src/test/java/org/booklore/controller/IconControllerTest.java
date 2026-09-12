package org.booklore.controller;

import org.booklore.service.IconService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IconControllerTest {

    @Test
    void svgContentIsServedLockedDown_soAnEmbeddedScriptCannotRun() {
        IconService iconService = mock(IconService.class);
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(document.cookie)</script></svg>";
        when(iconService.getSvgIcon("evil")).thenReturn(svg);

        ResponseEntity<String> response = new IconController(iconService).getSvgIconContent("evil");

        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).startsWith("default-src 'none'");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("image/svg+xml");
        assertThat(response.getBody()).isEqualTo(svg);
    }
}
