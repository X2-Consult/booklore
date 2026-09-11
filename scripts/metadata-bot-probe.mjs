#!/usr/bin/env node
// Probe: can a real (headless) browser get past Amazon's and GoodReads' bot checks from this
// server, where Booklore's plain Jsoup requests get challenged?
//
// For each sample book it fetches the same pages Booklore's metadata parsers use, three ways:
//   http    - plain HTTP with Booklore's headers (the baseline; what Jsoup sees)
//   browser - headless Chromium, one session for the whole run so solved challenges carry over
//   hybrid  - plain HTTP again, but sending the cookies the browser earned. If this works,
//             Booklore only needs a browser to mint cookies, not to render every page.
//
// Setup (once, in any scratch directory -- the script resolves 'playwright' from the cwd):
//   mkdir -p ~/bot-probe && cd ~/bot-probe
//   npm init -y >/dev/null && npm i playwright@1.63.0
//   npx playwright install chromium     # if Chromium won't start: sudo npx playwright install-deps chromium
//
// Run (from that directory):
//   node /opt/booklore/scripts/metadata-bot-probe.mjs --amazon-domain com.au
//   node /opt/booklore/scripts/metadata-bot-probe.mjs --modes browser --books 2 --dump ./dumps
//   --save-cookies cookies.json writes the browser session's cookies out for other clients to try.
//
// It makes about 3 requests per book per mode, spaced 3-5s apart: ~45 requests for the defaults.

import {createRequire} from 'node:module';
import {mkdirSync, writeFileSync} from 'node:fs';
import path from 'node:path';

const args = parseArgs(process.argv.slice(2));
const AMAZON_DOMAIN = args['amazon-domain'] ?? 'com';
const MODES = (args.modes ?? 'http,browser,hybrid').split(',').map(s => s.trim());
const DUMP_DIR = args.dump ?? null;
const CHALLENGE_WAIT_MS = 20_000;

const SAMPLE_BOOKS = [
  {title: 'Project Hail Mary', author: 'Andy Weir', isbn: '9780593135204'},
  {title: 'The Martian', author: 'Andy Weir', isbn: '9780553418026'},
  {title: 'Dune', author: 'Frank Herbert', isbn: '9780441172719'},
  {title: 'The Final Empire', author: 'Brandon Sanderson', isbn: '9780765311788'},
  {title: 'The Hobbit', author: 'J.R.R. Tolkien', isbn: '9780547928227'},
].slice(0, Number(args.books ?? 5));

// Same headers AmazonBookParser / GoodReadsParser send.
const CHROME_UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36';
const AMAZON_HEADERS = {
  'accept': 'text/html, application/json',
  'accept-language': 'en-GB,en;q=0.9',
  'sec-ch-ua': '"Google Chrome";v="137", "Chromium";v="137", "Not_A Brand";v="24"',
  'sec-ch-ua-mobile': '?0',
  'sec-ch-ua-platform': '"macOS"',
  'sec-fetch-dest': 'empty',
  'sec-fetch-mode': 'cors',
  'sec-fetch-site': 'same-origin',
  'user-agent': CHROME_UA,
  'x-requested-with': 'XMLHttpRequest',
};
const GOODREADS_HEADERS = {
  'accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
  'accept-language': 'en-US,en;q=0.9',
  'sec-ch-ua': '"Google Chrome";v="131", "Chromium";v="131", "Not_A Brand";v="24"',
  'sec-ch-ua-mobile': '?0',
  'sec-ch-ua-platform': '"macOS"',
  'sec-fetch-dest': 'document',
  'sec-fetch-mode': 'navigate',
  'sec-fetch-site': 'none',
  'user-agent': CHROME_UA.replace('137', '131'),
};

// --- page classification: mirrors the checks in the Booklore parsers -----------------------

function classifyAmazon(status, html, kind) {
  if (status === 503) return 'BLOCKED_503';
  if (/\/_sec\/verify|bm-verify/.test(html)) return 'CHALLENGE_AKAMAI';
  if (/validateCaptcha|opfcaptcha/.test(html)) return 'CAPTCHA';
  if (kind === 'search') return html.includes('data-component-type="s-search-results"') ? 'OK' : 'NO_RESULTS';
  return /id="productTitle"|id="ebooksProductTitle"/.test(html) ? 'OK' : 'UNEXPECTED';
}

function classifyGoodreads(status, html) {
  if (status === 202) return 'CHALLENGE_WAF';
  // Check for content first: real book pages served with a WAF token also embed challenge.js.
  if (html.includes('__NEXT_DATA__') && html.includes('apolloState')) return 'OK';
  if (/awsWafCookieDomainList|AwsWafIntegration|id="challenge-container"|challenge\.js/.test(html)) return 'CHALLENGE_WAF';
  return 'UNEXPECTED';
}

const classify = (provider, status, html, kind) =>
  provider === 'amazon' ? classifyAmazon(status, html, kind) : classifyGoodreads(status, html);

function firstAsin(html) {
  const m = html.match(/data-asin="([A-Z0-9]{10})"/);
  return m ? m[1] : null;
}

// --- fetchers ---------------------------------------------------------------------------------

async function fetchPlain(provider, url, cookieHeader) {
  const headers = {...(provider === 'amazon' ? AMAZON_HEADERS : GOODREADS_HEADERS)};
  if (cookieHeader) headers.cookie = cookieHeader;
  const res = await fetch(url, {headers, redirect: 'follow'});
  return {status: res.status, html: await res.text()};
}

async function fetchInBrowser(page, provider, url, kind) {
  const response = await page.goto(url, {waitUntil: 'domcontentloaded', timeout: 45_000});
  let status = response?.status() ?? 0;
  let html = await page.content();
  // Challenge pages solve themselves in JS and then reload; give them time to do it.
  const deadline = Date.now() + CHALLENGE_WAIT_MS;
  while (classify(provider, status, html, kind) !== 'OK' && Date.now() < deadline) {
    await page.waitForTimeout(1000);
    html = await page.content().catch(() => html);
    status = 200; // the reload replaced the challenge document
  }
  return {status, html};
}

// --- run ----------------------------------------------------------------------------------------

const results = [];

async function probe(mode, provider, kind, url, fetcher) {
  const started = Date.now();
  let status = 0, html = '', outcome;
  try {
    ({status, html} = await fetcher());
    outcome = classify(provider, status, html, kind);
  } catch (e) {
    outcome = 'ERROR ' + (e.message ?? e).toString().split('\n')[0].slice(0, 60);
  }
  const ms = Date.now() - started;
  results.push({mode, provider, kind, outcome, status, ms, url});
  console.log(`${mode.padEnd(8)} ${provider.padEnd(9)} ${kind.padEnd(7)} ${outcome.padEnd(17)} ${String(status).padEnd(4)} ${String(ms).padStart(6)}ms  ${url}`);
  if (DUMP_DIR && outcome !== 'OK') {
    mkdirSync(DUMP_DIR, {recursive: true});
    writeFileSync(path.join(DUMP_DIR, `${mode}-${provider}-${kind}-${results.length}.html`), html);
  }
  await pause();
  return {outcome, html};
}

async function runBook(mode, book, fetchFor) {
  const grUrl = `https://www.goodreads.com/book/isbn/${book.isbn}`;
  await probe(mode, 'goodreads', 'book', grUrl, fetchFor('goodreads', grUrl, 'book'));

  const q = encodeURIComponent(`${book.title} ${book.author}`).replace(/%20/g, '+');
  const searchUrl = `https://www.amazon.${AMAZON_DOMAIN}/s?k=${q}`;
  const search = await probe(mode, 'amazon', 'search', searchUrl, fetchFor('amazon', searchUrl, 'search'));
  const asin = search.outcome === 'OK' ? firstAsin(search.html) : null;
  if (asin) {
    const dpUrl = `https://www.amazon.${AMAZON_DOMAIN}/dp/${asin}`;
    await probe(mode, 'amazon', 'detail', dpUrl, fetchFor('amazon', dpUrl, 'detail'));
  }
}

async function main() {
  console.log(`Probing ${SAMPLE_BOOKS.length} books, modes=${MODES.join(',')}, amazon.${AMAZON_DOMAIN}\n`);
  console.log(`${'mode'.padEnd(8)} ${'provider'.padEnd(9)} ${'kind'.padEnd(7)} ${'outcome'.padEnd(17)} code   time    url`);

  if (MODES.includes('http')) {
    for (const book of SAMPLE_BOOKS) {
      await runBook('http', book, (provider, url) => () => fetchPlain(provider, url));
    }
  }

  let browserCookies = [];
  if (MODES.includes('browser') || MODES.includes('hybrid')) {
    const {chromium} = loadPlaywright();
    // Full Chromium in new-headless mode is much closer to a desktop browser than the
    // stripped headless shell; its default UA still says "HeadlessChrome", so replace that.
    const browser = await chromium.launch({headless: true, channel: 'chromium'});
    const version = browser.version();
    const context = await browser.newContext({
      userAgent: `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${version} Safari/537.36`,
      locale: 'en-GB',
      viewport: {width: 1366, height: 900},
    });
    const page = await context.newPage();
    try {
      for (const book of SAMPLE_BOOKS) {
        await runBook('browser', book, (provider, url, kind) => () => fetchInBrowser(page, provider, url, kind));
      }
      browserCookies = await context.cookies();
      if (args['save-cookies']) {
        writeFileSync(args['save-cookies'], JSON.stringify(browserCookies, null, 2));
        console.log(`\nSaved ${browserCookies.length} browser cookies to ${args['save-cookies']}`);
      }
    } finally {
      await browser.close();
    }
  }

  if (MODES.includes('hybrid')) {
    const cookieHeaderFor = domain => browserCookies
      .filter(c => domain.endsWith(c.domain.replace(/^\./, '')))
      .map(c => `${c.name}=${c.value}`).join('; ');
    const grCookies = cookieHeaderFor('www.goodreads.com');
    const amzCookies = cookieHeaderFor(`www.amazon.${AMAZON_DOMAIN}`);
    console.log(`\nhybrid: reusing browser cookies -- goodreads: ${cookieNames(grCookies)}; amazon: ${cookieNames(amzCookies)}`);
    for (const book of SAMPLE_BOOKS) {
      await runBook('hybrid', book, (provider, url) => () =>
        fetchPlain(provider, url, provider === 'amazon' ? amzCookies : grCookies));
    }
  }

  printSummary();
}

main().catch(e => {
  console.error(e);
  process.exit(1);
});

// --- helpers ------------------------------------------------------------------------------------

function loadPlaywright() {
  // Resolve from the cwd (the scratch dir it was installed into), not from this script's folder.
  const require = createRequire(path.join(process.cwd(), 'noop.js'));
  try {
    return require('playwright');
  } catch {
    console.error("Can't find the 'playwright' package from this directory. See the setup lines at the top of this script.");
    process.exit(1);
  }
}

function pause() {
  return new Promise(r => setTimeout(r, 3000 + Math.random() * 2000));
}

function cookieNames(header) {
  return header ? header.split('; ').map(c => c.split('=')[0]).join(',') : '(none)';
}

function printSummary() {
  console.log('\nSummary (OK / attempted):');
  const groups = new Map();
  for (const r of results) {
    const key = `${r.mode.padEnd(8)} ${r.provider.padEnd(9)} ${r.kind}`;
    const g = groups.get(key) ?? {ok: 0, n: 0, other: new Map()};
    g.n++;
    if (r.outcome === 'OK') g.ok++;
    else g.other.set(r.outcome, (g.other.get(r.outcome) ?? 0) + 1);
    groups.set(key, g);
  }
  for (const [key, g] of groups) {
    const other = [...g.other].map(([k, v]) => `${k} x${v}`).join(', ');
    console.log(`  ${key.padEnd(26)} ${g.ok}/${g.n}${other ? '   ' + other : ''}`);
  }
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (!argv[i].startsWith('--')) continue;
    const key = argv[i].slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) out[key] = true;
    else out[key] = argv[++i];
  }
  return out;
}
