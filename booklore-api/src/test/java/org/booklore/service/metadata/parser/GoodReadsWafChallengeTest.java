package org.booklore.service.metadata.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoodReadsWafChallengeTest {

    // Trimmed from the HTTP 202 interstitial GoodReads serves a plain HTTP client.
    private static final String WAF_INTERSTITIAL = """
            <!DOCTYPE html><html lang="en"><head><title></title>
            <script type="text/javascript">window.awsWafCookieDomainList = []; window.gokuProps = {"key":"AQIDAH"};</script>
            <script src="https://ea457862827c.aa0f107e.ap-southeast-1.token.awswaf.com/ea457862827c/ca63136f72fb/b63fc4339d15/challenge.js"></script>
            </head><body><div id="challenge-container"></div>
            <script type="text/javascript">AwsWafIntegration.getToken().then(() => { window.location.reload(true); });</script>
            </body></html>
            """;

    // A real book page, as served once the client holds an aws-waf-token: it still loads the
    // WAF SDK's challenge.js alongside the Next.js payload.
    private static final String BOOK_PAGE_WITH_WAF_SDK = """
            <!DOCTYPE html><html><head>
            <script src="https://b13c77965523.us-east-1.sdk.awswaf.com/b13c77965523/d42dcdc33310/challenge.js" data-nscript="afterInteractive"></script>
            </head><body>
            <script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"apolloState":{}}}}</script>
            </body></html>
            """;

    @Test
    void interstitialIsAChallenge() {
        assertThat(GoodReadsParser.isWafChallenge(200, WAF_INTERSTITIAL)).isTrue();
        assertThat(GoodReadsParser.isWafChallenge(202, "")).isTrue();
    }

    @Test
    void bookPageLoadingTheWafSdkIsNotAChallenge() {
        assertThat(GoodReadsParser.isWafChallenge(200, BOOK_PAGE_WITH_WAF_SDK)).isFalse();
    }
}
