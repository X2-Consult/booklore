package org.booklore.service.metadata.parser;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.dto.settings.MetadataPublicReviewsSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The parsers take pages from the headless browser when it's available, and react to pages it couldn't get past. */
class BrowserRoutingParserTest {

    private final AppSettingService appSettingService = mock(AppSettingService.class);
    private final MetadataProviderGuard providerGuard = mock(MetadataProviderGuard.class);
    private final BrowserPageFetcher browser = mock(BrowserPageFetcher.class);

    @BeforeEach
    void setUp() {
        MetadataProviderSettings providerSettings = new MetadataProviderSettings();
        MetadataProviderSettings.Amazon amazon = new MetadataProviderSettings.Amazon();
        amazon.setEnabled(true);
        amazon.setDomain("com.au");
        amazon.setCookie("session-id=abc");
        providerSettings.setAmazon(amazon);
        when(appSettingService.getAppSettings()).thenReturn(AppSettings.builder()
                .metadataProviderSettings(providerSettings)
                .metadataPublicReviewsSettings(MetadataPublicReviewsSettings.builder().providers(Set.of()).build())
                .build());
        when(browser.isAvailable()).thenReturn(true);
    }

    private static BrowserPageFetcher.FetchedPage page(String url, String html, boolean ready) {
        return new BrowserPageFetcher.FetchedPage(url, 200, html, ready);
    }

    @Nested
    class GoodReads {

        private static final String BOOK_URL = "https://www.goodreads.com/book/show/54493401";
        private static final String BOOK_PAGE = """
                <html><head><script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"apolloState":{
                  "Book:kca://book/real": {"title": "Project Hail Mary", "description": "Full description.",
                    "details": {"asin": "B08FHBV4ZX", "numPages": 496}}
                }}}}
                </script></head><body></body></html>
                """;

        private final GoodReadsParser parser = new GoodReadsParser(appSettingService, providerGuard, browser);
        private final Book book = Book.builder().id(1L)
                .metadata(BookMetadata.builder().goodreadsId("54493401").build()).build();

        @Test
        void readsTheBookPageFromTheBrowser() {
            when(browser.fetch(eq(BOOK_URL), anyMap(), any())).thenReturn(Optional.of(page(BOOK_URL, BOOK_PAGE, true)));

            BookMetadata result = parser.fetchTopMetadata(book, FetchMetadataRequest.builder().build());

            assertThat(result.getTitle()).isEqualTo("Project Hail Mary");
            assertThat(result.getDescription()).isEqualTo("Full description.");
            assertThat(result.getAsin()).isEqualTo("B08FHBV4ZX");
            verify(providerGuard, never()).markBlocked(any());
        }

        @Test
        void readinessCheckWaitsOutTheWafInterstitial_butAcceptsARealPageLoadingTheWafSdk() {
            when(browser.fetch(eq(BOOK_URL), anyMap(), any())).thenReturn(Optional.of(page(BOOK_URL, BOOK_PAGE, true)));
            parser.fetchTopMetadata(book, FetchMetadataRequest.builder().build());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Predicate<String>> isReady = ArgumentCaptor.forClass(Predicate.class);
            verify(browser).fetch(eq(BOOK_URL), anyMap(), isReady.capture());
            assertThat(isReady.getValue().test("<div id=\"challenge-container\"></div><script src=\"https://x.token.awswaf.com/challenge.js\"></script>")).isFalse();
            assertThat(isReady.getValue().test(BOOK_PAGE + "<script src=\"https://x.sdk.awswaf.com/challenge.js\"></script>")).isTrue();
        }

        @Test
        void pageStillGatedInTheBrowser_marksGoodReadsBlocked() {
            when(browser.fetch(eq(BOOK_URL), anyMap(), any()))
                    .thenReturn(Optional.of(page(BOOK_URL, "<div id=\"challenge-container\"></div>", false)));

            BookMetadata result = parser.fetchTopMetadata(book, FetchMetadataRequest.builder().build());

            assertThat(result).isNull();
            verify(providerGuard).markBlocked(MetadataProvider.GoodReads);
        }
    }

    @Nested
    class Amazon {

        private static final String SEARCH_URL = "https://www.amazon.com.au/s?k=9780593135204";
        private static final String DETAIL_URL = "https://www.amazon.com.au/dp/1529157463";
        private static final String SEARCH_PAGE = """
                <html><body><span data-component-type="s-search-results">
                <div role="listitem" data-index="1" data-asin="1529157463"><div data-cy="title-recipe">Project Hail Mary</div></div>
                </span></body></html>
                """;
        private static final String DETAIL_PAGE = "<html><body><span id=\"productTitle\">Project Hail Mary</span></body></html>";

        private final AmazonBookParser parser = new AmazonBookParser(appSettingService, providerGuard, browser);
        private final FetchMetadataRequest request = FetchMetadataRequest.builder().isbn("9780593135204").build();

        @Test
        void searchesAndReadsTheProductPageThroughTheBrowser_inTheStoresLanguage() {
            when(browser.fetch(eq(SEARCH_URL), anyMap(), any())).thenReturn(Optional.of(page(SEARCH_URL, SEARCH_PAGE, true)));
            when(browser.fetch(eq(DETAIL_URL), anyMap(), any())).thenReturn(Optional.of(page(DETAIL_URL, DETAIL_PAGE, true)));

            BookMetadata result = parser.fetchTopMetadata(Book.builder().id(1L).build(), request);

            assertThat(result.getTitle()).isEqualTo("Project Hail Mary");
            verify(browser).fetch(eq(SEARCH_URL), eq(Map.of("Accept-Language", "en-GB,en;q=0.9")), any());
            verify(providerGuard, never()).markBlocked(any(), any());
        }

        @Test
        void challengeTheBrowserCouldNotPass_blocksAmazonForThatCookie() {
            when(browser.fetch(eq(SEARCH_URL), anyMap(), any()))
                    .thenReturn(Optional.of(page(SEARCH_URL, "<script>xhr.open(\"POST\", \"/_sec/verify?provider=interstitial\")</script>", false)));

            BookMetadata result = parser.fetchTopMetadata(Book.builder().id(1L).build(), request);

            assertThat(result).isNull();
            verify(providerGuard).markBlocked(MetadataProvider.Amazon, "session-id=abc");
            verify(browser, times(1)).fetch(any(), anyMap(), any());
        }

        @Test
        void knownAsin_goesStraightToTheProductPage_withoutSearching() {
            String dpUrl = "https://www.amazon.com.au/dp/B08FFJS3YW";
            when(browser.fetch(eq(dpUrl), anyMap(), any())).thenReturn(Optional.of(page(dpUrl, DETAIL_PAGE, true)));

            BookMetadata result = parser.fetchTopMetadata(Book.builder().id(1L).build(),
                    FetchMetadataRequest.builder().asin("b08ffjs3yw").isbn("9780593135204").build());

            assertThat(result.getTitle()).isEqualTo("Project Hail Mary");
            verify(browser, times(1)).fetch(any(), anyMap(), any());
        }

        @Test
        void knownAsinWithNoProductPageHere_fallsBackToSearch() {
            String dpUrl = "https://www.amazon.com.au/dp/B0NOTINAU1";
            when(browser.fetch(eq(dpUrl), anyMap(), any()))
                    .thenReturn(Optional.of(page(dpUrl, "<html><body>Sorry! We couldn't find that page.</body></html>", true)));
            when(browser.fetch(eq(SEARCH_URL), anyMap(), any())).thenReturn(Optional.of(page(SEARCH_URL, SEARCH_PAGE, true)));
            when(browser.fetch(eq(DETAIL_URL), anyMap(), any())).thenReturn(Optional.of(page(DETAIL_URL, DETAIL_PAGE, true)));

            BookMetadata result = parser.fetchTopMetadata(Book.builder().id(1L).build(),
                    FetchMetadataRequest.builder().asin("B0NOTINAU1").isbn("9780593135204").build());

            assertThat(result.getTitle()).isEqualTo("Project Hail Mary");
            verify(browser).fetch(eq(SEARCH_URL), anyMap(), any());
        }

        @Test
        void activeCooldown_skipsTheBrowserEntirely() {
            when(providerGuard.isBlocked(MetadataProvider.Amazon, "session-id=abc")).thenReturn(true);

            assertThat(parser.fetchTopMetadata(Book.builder().id(1L).build(), request)).isNull();

            verifyNoInteractions(browser);
        }
    }
}
