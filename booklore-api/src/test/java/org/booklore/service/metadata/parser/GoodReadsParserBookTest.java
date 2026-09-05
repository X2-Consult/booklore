package org.booklore.service.metadata.parser;

import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataPublicReviewsSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the {@code Book:kca:} node selection + description-key handling in
 * {@link GoodReadsParser#parseBookDetails}. A book page's apolloState often holds several
 * {@code Book} nodes (page book + "readers also enjoyed" stubs); the parser must pick the
 * one with the real {@code details} block, and read the description whether GoodReads stored
 * it under {@code description} or {@code description({"stripped":true})}.
 */
class GoodReadsParserBookTest {

    private GoodReadsParser parser;

    @BeforeEach
    void setUp() {
        AppSettingService appSettingService = mock(AppSettingService.class);
        AppSettings settings = AppSettings.builder()
                .metadataPublicReviewsSettings(MetadataPublicReviewsSettings.builder().providers(Set.of()).build())
                .build();
        when(appSettingService.getAppSettings()).thenReturn(settings);
        parser = new GoodReadsParser(appSettingService);
    }

    private Document docWithApolloState(String apolloStateJson) {
        String nextData = "{\"props\":{\"pageProps\":{\"apolloState\":" + apolloStateJson + "}}}";
        return Jsoup.parse("<html><head><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + nextData + "</script></head><body></body></html>");
    }

    @Test
    void picksTheBookNodeWithDetails_notATitleOnlyStub() {
        Document doc = docWithApolloState("""
                {
                  "Book:kca://book/stub": { "title": "Some Other Book" },
                  "Book:kca://book/real": {
                    "title": "The Final Empire",
                    "description({\\"stripped\\":true})": "Brandon Sanderson's epic fantasy debut.",
                    "bookGenres": [],
                    "details": { "numPages": 541, "isbn13": "9780765311788", "asin": "B002GYI9C4" }
                  }
                }
                """);

        BookMetadata result = parser.parseBookDetails(doc, "68428");

        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("The Final Empire");
        assertThat(result.getDescription()).isEqualTo("Brandon Sanderson's epic fantasy debut.");
        assertThat(result.getIsbn13()).isEqualTo("9780765311788");
        assertThat(result.getAsin()).isEqualTo("B002GYI9C4");
        assertThat(result.getPageCount()).isEqualTo(541);
    }

    @Test
    void readsPlainDescriptionKeyToo() {
        Document doc = docWithApolloState("""
                {
                  "Book:kca://book/real": {
                    "title": "A Book",
                    "description": "Plain description key.",
                    "details": { "asin": "B00XXXXXXX" }
                  }
                }
                """);

        BookMetadata result = parser.parseBookDetails(doc, "1");

        assertThat(result.getDescription()).isEqualTo("Plain description key.");
        assertThat(result.getAsin()).isEqualTo("B00XXXXXXX");
    }
}
