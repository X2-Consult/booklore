package org.booklore.service.metadata.parser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.impl.driver.Driver;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Loads Amazon/GoodReads pages in a real headless Chromium, for the sites that bot-check plain
 * HTTP clients (Amazon's Akamai interstitial, GoodReads' AWS WAF). A browser runs the challenge
 * JavaScript and presents a genuine TLS/navigator fingerprint, so it gets the real page.
 *
 * <p>Modelled on the Bookshelf backend's Playwright scrapers, which run this way against the
 * same sites in bulk:
 * <ul>
 *   <li>one browser and one page at a time. Playwright objects aren't thread-safe, so all browser
 *       work runs on a single dedicated thread and callers queue behind it;</li>
 *   <li>a fixed pause after every navigation, on top of {@link MetadataProviderGuard}'s pacing;</li>
 *   <li>the browser context (and its cookies) is replaced every {@value #RECYCLE_EVERY_PAGES} pages,
 *       or early after a run of pages that stayed challenged, and a fresh context opens each
 *       site's home page before its first real request;</li>
 *   <li>images, fonts, media and stylesheets aren't loaded;</li>
 *   <li>Chromium shuts down after a few idle minutes so it isn't holding memory between batches.</li>
 * </ul>
 *
 * <p>Chromium is installed into Playwright's cache on first use. If it can't be installed or
 * launched (no network, missing system libraries, the musl-based Docker image), the fetcher
 * reports itself unavailable for a while and the parsers carry on with plain HTTP.
 */
@Slf4j
@Component
public class BrowserPageFetcher {

    public record FetchedPage(String url, int status, String html, boolean ready) {}

    static final int RECYCLE_EVERY_PAGES = 25;
    static final int RECYCLE_AFTER_MISSES = 3;
    private static final Duration GOODREADS_NAV_DELAY = Duration.ofMillis(3000);
    private static final Duration DEFAULT_NAV_DELAY = Duration.ofMillis(2500);
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration NAVIGATION_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration CALLER_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration IDLE_SHUTDOWN = Duration.ofMinutes(5);
    private static final Duration RETRY_UNAVAILABLE_AFTER = Duration.ofMinutes(30);
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);
    private static final Set<String> SKIPPED_RESOURCE_TYPES = Set.of("image", "media", "font", "stylesheet");

    private static final String STEALTH_JS = """
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
            Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
            window.chrome = { runtime: {} };
            """;

    private final boolean enabled;
    private final ScheduledExecutorService browserThread;

    // Written on the browser thread, read by callers deciding whether to bother queueing.
    private volatile Instant unavailableUntil = Instant.MIN;

    // Everything below is touched only on the browser thread.
    private boolean chromiumInstalled;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private final Set<String> warmedHosts = new HashSet<>();
    private int pagesInContext;
    private int consecutiveMisses;
    private Instant lastUsed = Instant.now();

    public BrowserPageFetcher(AppProperties appProperties) {
        this.enabled = appProperties.getMetadataBrowser() == null || appProperties.getMetadataBrowser().isEnabled();
        this.browserThread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "metadata-browser");
            thread.setDaemon(true);
            return thread;
        });
        if (enabled) {
            browserThread.scheduleWithFixedDelay(this::closeIfIdle, 1, 1, TimeUnit.MINUTES);
        }
    }

    /** Whether a fetch is worth attempting right now; false means use plain HTTP instead. */
    public boolean isAvailable() {
        return enabled && Instant.now().isAfter(unavailableUntil);
    }

    /**
     * Loads {@code url} and waits up to {@link #READY_TIMEOUT} for {@code isReady} to accept the
     * page's HTML, since challenge pages solve themselves in JS and then reload. A page that never
     * becomes ready comes back with {@code ready=false} and its last HTML, so the caller can
     * classify it. Empty means the browser isn't usable and the caller should fall back to plain HTTP.
     */
    public Optional<FetchedPage> fetch(String url, Map<String, String> headers, Predicate<String> isReady) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            return browserThread.submit(() -> fetchOnBrowserThread(url, headers, isReady))
                    .get(CALLER_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (TimeoutException e) {
            log.warn("Metadata browser: timed out waiting for {}", url);
            return Optional.empty();
        } catch (ExecutionException e) {
            log.warn("Metadata browser: fetching {} failed: {}", url, firstLine(e.getCause()));
            return Optional.empty();
        }
    }

    private Optional<FetchedPage> fetchOnBrowserThread(String url, Map<String, String> headers, Predicate<String> isReady) {
        lastUsed = Instant.now();
        if (!ensureBrowser()) {
            return Optional.empty();
        }
        try {
            if (pagesInContext >= RECYCLE_EVERY_PAGES || consecutiveMisses >= RECYCLE_AFTER_MISSES) {
                log.info("Metadata browser: starting a fresh session ({} pages, {} consecutive blocked)", pagesInContext, consecutiveMisses);
                openContext();
            }
            page.setExtraHTTPHeaders(headers == null ? Map.of() : headers);
            warmUp(url);

            Response response = navigate(url);
            int status = response != null ? response.status() : 0;
            String html = content();
            long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
            while (!isReady.test(html) && System.nanoTime() < deadline) {
                page.waitForTimeout(1000);
                html = content();
            }
            boolean ready = isReady.test(html);
            consecutiveMisses = ready ? 0 : consecutiveMisses + 1;
            pagesInContext++;
            String finalUrl = page.url();
            page.waitForTimeout(navDelay(url).toMillis());
            lastUsed = Instant.now();
            return Optional.of(new FetchedPage(finalUrl, status, html, ready));
        } catch (PlaywrightException e) {
            // A crashed page or browser: throw the session away and let the next fetch start clean.
            log.warn("Metadata browser: error loading {}, restarting the browser: {}", url, firstLine(e));
            closeBrowser();
            return Optional.empty();
        }
    }

    private boolean ensureBrowser() {
        if (browser != null && browser.isConnected() && page != null && !page.isClosed()) {
            return true;
        }
        closeBrowser();
        try {
            Map<String, String> env = new HashMap<>();
            // Playwright would otherwise download Chromium, Firefox and WebKit on first create.
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            if (!chromiumInstalled) {
                installChromium(env);
                chromiumInstalled = true;
            }
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setChannel("chromium")
                    .setArgs(List.of("--disable-blink-features=AutomationControlled", "--disable-dev-shm-usage", "--disable-gpu")));
            log.info("Metadata browser: started Chromium {}", browser.version());
            openContext();
            return true;
        } catch (Exception e) {
            unavailableUntil = Instant.now().plus(RETRY_UNAVAILABLE_AFTER);
            log.warn("Metadata browser: Chromium unavailable, using plain HTTP for {} min. Cause: {}",
                    RETRY_UNAVAILABLE_AFTER.toMinutes(), firstLine(e));
            closeBrowser();
            return false;
        }
    }

    private void installChromium(Map<String, String> env) throws Exception {
        Driver driver = Driver.ensureDriverInstalled(env, false);
        ProcessBuilder install = driver.createProcessBuilder();
        install.command().addAll(List.of("install", "chromium"));
        install.environment().putAll(env);
        install.environment().remove("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD");
        install.redirectErrorStream(true);
        log.info("Metadata browser: making sure Chromium is installed (first run downloads ~200 MB)");
        Process process = install.start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(INSTALL_TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("Chromium install timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Chromium install failed: " + output.strip());
        }
    }

    private void openContext() {
        if (context != null) {
            context.close();
        }
        String majorVersion = browser.version().split("\\.")[0];
        context = browser.newContext(new Browser.NewContextOptions()
                // Chromium's own headless UA says "HeadlessChrome"; present as the desktop build it is.
                .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + majorVersion + ".0.0.0 Safari/537.36")
                .setViewportSize(1920, 1080)
                .setLocale("en-US"));
        context.addInitScript(STEALTH_JS);
        context.route("**/*", route -> {
            if (SKIPPED_RESOURCE_TYPES.contains(route.request().resourceType())) {
                route.abort();
            } else {
                route.resume();
            }
        });
        context.setDefaultNavigationTimeout(NAVIGATION_TIMEOUT.toMillis());
        page = context.newPage();
        warmedHosts.clear();
        pagesInContext = 0;
        consecutiveMisses = 0;
    }

    // A fresh session's first request is to the site's home page, like a person arriving there,
    // so bot-management cookies get set before we ask for a search or product page.
    private void warmUp(String url) {
        URI uri = URI.create(url);
        if (!warmedHosts.add(uri.getHost())) {
            return;
        }
        try {
            navigate(uri.getScheme() + "://" + uri.getHost() + "/");
            page.waitForTimeout(navDelay(url).toMillis());
        } catch (PlaywrightException e) {
            log.debug("Metadata browser: warm-up for {} failed (non-fatal): {}", uri.getHost(), firstLine(e));
        }
    }

    private Response navigate(String url) {
        try {
            return page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        } catch (PlaywrightException e) {
            if (String.valueOf(e.getMessage()).contains("net::ERR")) {
                throw e;
            }
            // Challenge pages reload themselves mid-navigation; the content is still worth reading.
            log.debug("Metadata browser: soft navigation error for {}: {}", url, firstLine(e));
            return null;
        }
    }

    private String content() {
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return page.content();
            } catch (PlaywrightException e) {
                // "page is navigating": a challenge page reloading itself.
                page.waitForTimeout(1000);
            }
        }
        return "";
    }

    private static Duration navDelay(String url) {
        return url.contains("goodreads.com") ? GOODREADS_NAV_DELAY : DEFAULT_NAV_DELAY;
    }

    private void closeIfIdle() {
        if (playwright != null && Duration.between(lastUsed, Instant.now()).compareTo(IDLE_SHUTDOWN) > 0) {
            log.info("Metadata browser: idle for {} min, shutting Chromium down", IDLE_SHUTDOWN.toMinutes());
            closeBrowser();
        }
    }

    private void closeBrowser() {
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception e) {
            log.debug("Metadata browser: error closing Playwright: {}", firstLine(e));
        } finally {
            playwright = null;
            browser = null;
            context = null;
            page = null;
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            browserThread.submit(this::closeBrowser).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Metadata browser: error during shutdown: {}", firstLine(e));
        }
        browserThread.shutdownNow();
    }

    private static String firstLine(Throwable t) {
        String message = t == null ? null : t.getMessage();
        return message == null ? String.valueOf(t) : message.lines().findFirst().orElse(message);
    }
}
