package org.booklore.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final AppProperties appProperties;

    @Bean
    public RestClient restClient() {
        // Timeouts so a hung provider can't stall a metadata batch indefinitely (Grimmory ef041dd4).
        HttpClientSettings settings = HttpClientSettings.defaults().withTimeouts(connectTimeout(), readTimeout());
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .build();
    }

    /**
     * Refuses to connect to multicast addresses and to anything in {@code app.outbound.restricted-ranges}.
     * Spring checks the resolved address on every request, redirects included, which a URL check made
     * before the request can't do (Grimmory 31b574d3).
     */
    @Bean
    public InetAddressFilter userUrlAddressFilter() {
        InetAddressFilter filter = InetAddressFilter.not(InetAddressFilter.multicast());
        var restrictedRanges = appProperties.getOutbound().getRestrictedRanges();
        if (restrictedRanges != null && !restrictedRanges.isEmpty()) {
            filter = filter.andNot(restrictedRanges.toArray(String[]::new));
        }
        return filter;
    }

    /**
     * For URLs a user supplies (cover and author-photo downloads). Redirects are left to the caller,
     * which follows them itself to keep the original Host header when a CDN redirects to a raw IP;
     * every hop still goes through the address filter.
     */
    @Bean("noRedirectRestTemplate")
    public RestTemplate noRedirectRestTemplate(InetAddressFilter userUrlAddressFilter) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(connectTimeout(), readTimeout())
                .withRedirects(HttpRedirects.DONT_FOLLOW)
                .withInetAddressFilter(userUrlAddressFilter);
        return new RestTemplate(ClientHttpRequestFactoryBuilder.jdk().build(settings));
    }

    @Bean
    public RestTemplate oidcRestTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }

    private Duration connectTimeout() {
        return Duration.ofSeconds(appProperties.getOutbound().getConnectTimeout());
    }

    private Duration readTimeout() {
        return Duration.ofSeconds(appProperties.getOutbound().getReadTimeout());
    }
}
