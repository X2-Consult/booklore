package org.booklore.service.metadata.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AmazonBookParserBotChallengeTest {

    // Trimmed from a real amazon.com.au search response (HTTP 200) served to a Jsoup request.
    private static final String AKAMAI_INTERSTITIAL = """
            <!DOCTYPE html><html><head><meta http-equiv="refresh" content="5; URL='/s?k=Project+Hail+Mary&bm-verify=AAQAAAAN'" />
            <title>&nbsp;</title><script> var i = 1789167327; var j = i + Number("9882" + "56466"); </script></head>
            <body><script> function triggerInterstitialChallenge() { var xhr = new XMLHttpRequest();
            xhr.open("POST", "/_sec/verify?provider=interstitial", false); } </script></body></html>
            """;

    private static final String CLASSIC_CAPTCHA = """
            <html><body><form method="get" action="/errors/validateCaptcha" name="">
            <img src="https://images-na.ssl-images-amazon.com/captcha/opfcaptcha-prod/abc.jpg">
            </form></body></html>
            """;

    private static final String SEARCH_RESULTS = """
            <html><body><span data-component-type="s-search-results">
            <div role="listitem" data-index="1" data-asin="0593135202"></div>
            </span></body></html>
            """;

    @Test
    void detectsAkamaiInterstitial() {
        assertThat(AmazonBookParser.isBotChallenge(AKAMAI_INTERSTITIAL)).isTrue();
    }

    @Test
    void detectsClassicCaptcha() {
        assertThat(AmazonBookParser.isBotChallenge(CLASSIC_CAPTCHA)).isTrue();
    }

    @Test
    void realContentIsNotAChallenge() {
        assertThat(AmazonBookParser.isBotChallenge(SEARCH_RESULTS)).isFalse();
        assertThat(AmazonBookParser.isBotChallenge(null)).isFalse();
    }
}
