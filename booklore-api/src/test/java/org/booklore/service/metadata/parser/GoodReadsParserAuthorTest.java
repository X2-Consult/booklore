package org.booklore.service.metadata.parser;

import org.booklore.model.dto.AuthorSearchResult;
import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.service.appsettings.AppSettingService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit coverage for {@link GoodReadsParser#extractAuthorFromBookDocument} - the apolloState
 * parsing logic behind "fetch author details from a linked GoodReads book". The exact field
 * names GoodReads uses in the {@code Contributor} node are approximated here; the parser also
 * accepts {@code imageUrl}/{@code image} as image aliases and {@code description(...)} arg-suffixed keys.
 */
class GoodReadsParserAuthorTest {

    private final GoodReadsParser parser = new GoodReadsParser(mock(AppSettingService.class), mock(MetadataProviderGuard.class));

    private Document docWithApolloState(String apolloStateJson) {
        String nextData = "{\"props\":{\"pageProps\":{\"apolloState\":" + apolloStateJson + "}}}";
        return Jsoup.parse("<html><head><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + nextData + "</script></head><body></body></html>");
    }

    @Test
    void extractsName_bio_image_and_goodreadsId_fromSingleContributor() {
        Document doc = docWithApolloState("""
                {
                  "Contributor:kca://author/amzn1.gr.author.v1.abc": {
                    "__typename": "Contributor",
                    "name": "Brandon Sanderson",
                    "description": "<p>Brandon Sanderson is an American author of <b>epic fantasy</b>.</p>",
                    "profileImageUrl": "https://images.gr-assets.com/authors/sanderson.jpg",
                    "webUrl": "https://www.goodreads.com/author/show/38550.Brandon_Sanderson"
                  }
                }
                """);

        AuthorSearchResult result = parser.extractAuthorFromBookDocument(doc, "Brandon Sanderson");

        assertThat(result).isNotNull();
        assertThat(result.getSource()).isEqualTo(AuthorMetadataSource.GOODREADS);
        assertThat(result.getName()).isEqualTo("Brandon Sanderson");
        assertThat(result.getDescription()).isEqualTo("Brandon Sanderson is an American author of epic fantasy.");
        assertThat(result.getImageUrl()).isEqualTo("https://images.gr-assets.com/authors/sanderson.jpg");
        assertThat(result.getGoodreadsId()).isEqualTo("38550");
    }

    @Test
    void picksContributorMatchingTheNameHint_onMultiAuthorBook() {
        Document doc = docWithApolloState("""
                {
                  "Contributor:kca://author/one": {
                    "name": "Ghostwriter Smith",
                    "description": "Not the one.",
                    "webUrl": "https://www.goodreads.com/author/show/111.Ghostwriter_Smith"
                  },
                  "Contributor:kca://author/two": {
                    "name": "James S. A. Corey",
                    "description": "Pen name of two authors.",
                    "webUrl": "https://www.goodreads.com/author/show/4192148.James_S_A_Corey"
                  }
                }
                """);

        AuthorSearchResult result = parser.extractAuthorFromBookDocument(doc, "James S.A. Corey");

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("James S. A. Corey");
        assertThat(result.getGoodreadsId()).isEqualTo("4192148");
    }

    @Test
    void acceptsArgSuffixedDescriptionKey_andImageAlias() {
        Document doc = docWithApolloState("""
                {
                  "Contributor:kca://author/x": {
                    "name": "Test Author",
                    "description({\\"stripped\\":true})": "Plain bio text.",
                    "image": "https://example.com/x.jpg",
                    "webUrl": "https://www.goodreads.com/author/show/9.Test_Author"
                  }
                }
                """);

        AuthorSearchResult result = parser.extractAuthorFromBookDocument(doc, "Test Author");

        assertThat(result).isNotNull();
        assertThat(result.getDescription()).isEqualTo("Plain bio text.");
        assertThat(result.getImageUrl()).isEqualTo("https://example.com/x.jpg");
    }

    @Test
    void returnsNull_whenNoNextData() {
        Document doc = Jsoup.parse("<html><body><p>WAF challenge</p></body></html>");
        assertThat(parser.extractAuthorFromBookDocument(doc, "Anyone")).isNull();
    }

    @Test
    void returnsNull_whenNoContributorNode() {
        Document doc = docWithApolloState("{\"Book:kca://book/1\":{\"title\":\"Some Book\"}}");
        assertThat(parser.extractAuthorFromBookDocument(doc, "Anyone")).isNull();
    }
}
