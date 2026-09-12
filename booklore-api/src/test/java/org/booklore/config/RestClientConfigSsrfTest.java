package org.booklore.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.FilteredHostException;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The user-URL client must refuse to connect into restricted ranges; here, a server on loopback. */
class RestClientConfigSsrfTest {

    private HttpServer server;
    private String url;

    @BeforeEach
    void startLoopbackServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/secret", exchange -> {
            byte[] body = "internal".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/secret";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private RestTemplate userUrlClient(List<String> restrictedRanges) {
        AppProperties properties = new AppProperties();
        properties.getOutbound().setRestrictedRanges(restrictedRanges);
        RestClientConfig config = new RestClientConfig(properties);
        return config.noRedirectRestTemplate(config.userUrlAddressFilter());
    }

    @Test
    void refusesToConnectIntoARestrictedRange() {
        RestTemplate client = userUrlClient(List.of("127.0.0.0/8", "10.0.0.0/8"));

        assertThatThrownBy(() -> client.getForObject(url, String.class))
                .isInstanceOf(FilteredHostException.class);
    }

    @Test
    void connectsWhenTheRangeIsNotRestricted() {
        RestTemplate client = userUrlClient(List.of("10.0.0.0/8"));

        assertThat(client.getForObject(url, String.class)).isEqualTo("internal");
    }
}
