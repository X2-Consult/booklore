package org.booklore.service.metadata.parser;

import org.booklore.config.AppProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Chromium against the real sites -- never part of a normal build. Run by hand on a server
 * to confirm the browser gets past the bot checks from its IP (downloads Chromium on first run):
 * <pre>BOOKLORE_LIVE_BROWSER_TEST=true ./gradlew test --tests '*BrowserPageFetcherLiveTest'</pre>
 */
@EnabledIfEnvironmentVariable(named = "BOOKLORE_LIVE_BROWSER_TEST", matches = "true")
class BrowserPageFetcherLiveTest {

    private static BrowserPageFetcher fetcher;

    @BeforeAll
    static void start() {
        fetcher = new BrowserPageFetcher(new AppProperties());
    }

    @AfterAll
    static void stop() {
        fetcher.shutdown();
    }

    @Test
    void goodreadsBookPage() {
        Optional<BrowserPageFetcher.FetchedPage> page = fetcher.fetch(
                "https://www.goodreads.com/book/isbn/9780593135204", Map.of(), html -> !GoodReadsParser.isWafChallenge(200, html));

        assertThat(page).isPresent();
        assertThat(page.get().ready()).isTrue();
        assertThat(page.get().html()).contains("__NEXT_DATA__").contains("apolloState");
    }

    @Test
    void amazonSearchPage() {
        Optional<BrowserPageFetcher.FetchedPage> page = fetcher.fetch(
                "https://www.amazon.com.au/s?k=Project+Hail+Mary+Andy+Weir", Map.of("Accept-Language", "en-GB,en;q=0.9"),
                html -> !AmazonBookParser.isBotChallenge(html));

        assertThat(page).isPresent();
        assertThat(page.get().ready()).isTrue();
        assertThat(page.get().html()).contains("data-component-type=\"s-search-results\"");
    }
}
