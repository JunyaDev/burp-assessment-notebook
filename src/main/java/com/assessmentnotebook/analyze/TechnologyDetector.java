package com.assessmentnotebook.analyze;

import com.assessmentnotebook.model.Technology.Category;
import com.assessmentnotebook.model.Technology.Confidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Wappalyzer-in-spirit technology detector (spec §5) scaled for an extension:
 * it draws evidence from HTTP headers, cookies, meta tags, and script/link
 * URLs, extracting versions where the markup reveals them and attaching a
 * confidence to each signal. It never decides anything is authoritative — it
 * emits candidate {@link Detection}s that the controller merges into editable
 * records.
 */
public final class TechnologyDetector {

    /** One candidate technology with the single signal that produced it. */
    public static final class Detection {
        public final Category category;
        public final String name;
        public final String version;      // "" if unknown
        public final Confidence confidence;
        public final String evidenceSource;
        public final String evidenceDetail;

        public Detection(Category category, String name, String version, Confidence confidence,
                         String evidenceSource, String evidenceDetail) {
            this.category = category;
            this.name = name;
            this.version = version == null ? "" : version;
            this.confidence = confidence;
            this.evidenceSource = evidenceSource;
            this.evidenceDetail = evidenceDetail;
        }
    }

    /**
     * @param url         the response URL (for path-based markers)
     * @param headers     response headers as "Name: value" lines (may be null)
     * @param body        response body (HTML/JS; may be null)
     * @param contentType response content type (may be null)
     */
    public List<Detection> detect(String url, List<String> headers, String body, String contentType) {
        // Keyed by name so one response yields at most one detection per tech,
        // keeping the strongest signal.
        Map<String, Detection> found = new LinkedHashMap<>();

        detectFromHeaders(headers, found);
        detectFromCookies(headers, found);
        String html = (contentType == null || contentType.toLowerCase(Locale.ROOT).contains("html")
                || contentType.isBlank()) ? body : null;
        if (html != null) {
            detectFromMeta(html, found);
            detectFromMarkup(html, found);
        }
        detectFromUrls(url, html, found);

        return new ArrayList<>(found.values());
    }

    // ---- headers ---------------------------------------------------------

    private void detectFromHeaders(List<String> headers, Map<String, Detection> found) {
        String server = header(headers, "server");
        if (!server.isEmpty()) {
            matchBanner(server, "nginx", Category.WEB_SERVER, "nginx", found);
            matchBanner(server, "apache", Category.WEB_SERVER, "Apache", found);
            matchBanner(server, "microsoft-iis", Category.WEB_SERVER, "IIS", found);
            matchBanner(server, "litespeed", Category.WEB_SERVER, "LiteSpeed", found);
            matchBanner(server, "caddy", Category.WEB_SERVER, "Caddy", found);
            matchBanner(server, "openresty", Category.WEB_SERVER, "OpenResty", found);
            if (server.toLowerCase(Locale.ROOT).contains("cloudflare")) {
                add(found, Category.CDN, "Cloudflare", "", Confidence.HIGH,
                        "HTTP header", "Server: " + server);
            }
            if (server.toLowerCase(Locale.ROOT).contains("win")) {
                add(found, Category.OPERATING_SYSTEM, "Windows", "", Confidence.MEDIUM,
                        "HTTP header", "Server: " + server);
            }
        }
        String poweredBy = header(headers, "x-powered-by");
        if (!poweredBy.isEmpty()) {
            matchBanner(poweredBy, "php", Category.LANGUAGE, "PHP", found);
            matchBanner(poweredBy, "asp.net", Category.FRAMEWORK, "ASP.NET", found);
            matchBanner(poweredBy, "express", Category.FRAMEWORK, "Express", found);
            if (poweredBy.toLowerCase(Locale.ROOT).contains("express")) {
                add(found, Category.LANGUAGE, "Node.js", "", Confidence.MEDIUM,
                        "HTTP header", "X-Powered-By: " + poweredBy);
            }
        }
        addIfHeader(headers, "x-aspnet-version", Category.FRAMEWORK, "ASP.NET", found, true);
        addIfHeader(headers, "x-drupal-cache", Category.CMS, "Drupal", found, false);
        addIfHeader(headers, "x-generator", Category.CMS, null, found, false);
        addIfHeader(headers, "cf-ray", Category.CDN, "Cloudflare", found, false);
        addIfHeader(headers, "x-shopify-stage", Category.CMS, "Shopify", found, false);
        addIfHeader(headers, "x-vercel-id", Category.CDN, "Vercel", found, false);
    }

    private void detectFromCookies(List<String> headers, Map<String, Detection> found) {
        if (headers == null) return;
        for (String line : headers) {
            if (line == null || !line.toLowerCase(Locale.ROOT).startsWith("set-cookie:")) continue;
            String cookie = line.substring(line.indexOf(':') + 1).trim();
            String nameLower = cookie.contains("=")
                    ? cookie.substring(0, cookie.indexOf('=')).trim().toLowerCase(Locale.ROOT)
                    : cookie.toLowerCase(Locale.ROOT);
            cookieMarker(nameLower, "laravel_session", Category.FRAMEWORK, "Laravel", found);
            cookieMarker(nameLower, "xsrf-token", Category.FRAMEWORK, "Laravel", found);
            cookieMarker(nameLower, "phpsessid", Category.LANGUAGE, "PHP", found);
            cookieMarker(nameLower, "jsessionid", Category.LANGUAGE, "Java", found);
            cookieMarker(nameLower, "asp.net_sessionid", Category.FRAMEWORK, "ASP.NET", found);
            cookieMarker(nameLower, "ci_session", Category.FRAMEWORK, "CodeIgniter", found);
            cookieMarker(nameLower, "connect.sid", Category.FRAMEWORK, "Express", found);
            cookieMarker(nameLower, "csrftoken", Category.FRAMEWORK, "Django", found);
            cookieMarker(nameLower, "_session_id", Category.FRAMEWORK, "Ruby on Rails", found);
            if (nameLower.startsWith("wordpress_") || nameLower.startsWith("wp-")) {
                add(found, Category.CMS, "WordPress", "", Confidence.HIGH,
                        "cookie", nameLower);
            }
        }
    }

    // ---- meta / markup ---------------------------------------------------

    private static final Pattern META_GENERATOR = Pattern.compile(
            "<meta[^>]+name=[\"']generator[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NG_VERSION = Pattern.compile(
            "ng-version=[\"']([0-9][0-9.]*)[\"']", Pattern.CASE_INSENSITIVE);

    private void detectFromMeta(String html, Map<String, Detection> found) {
        Matcher m = META_GENERATOR.matcher(html);
        while (m.find()) {
            String content = m.group(1).trim();
            String lower = content.toLowerCase(Locale.ROOT);
            String version = firstVersion(content);
            if (lower.contains("wordpress")) {
                add(found, Category.CMS, "WordPress", version, Confidence.HIGH, "meta", content);
            } else if (lower.contains("drupal")) {
                add(found, Category.CMS, "Drupal", version, Confidence.HIGH, "meta", content);
            } else if (lower.contains("joomla")) {
                add(found, Category.CMS, "Joomla", version, Confidence.HIGH, "meta", content);
            } else if (lower.contains("hugo")) {
                add(found, Category.CMS, "Hugo", version, Confidence.HIGH, "meta", content);
            } else {
                add(found, Category.CMS, content.split("\\s")[0], version, Confidence.MEDIUM,
                        "meta", content);
            }
        }
    }

    private void detectFromMarkup(String html, Map<String, Detection> found) {
        String lower = html.toLowerCase(Locale.ROOT);
        Matcher ng = NG_VERSION.matcher(html);
        if (ng.find()) {
            add(found, Category.JS_FRAMEWORK, "Angular", ng.group(1), Confidence.HIGH,
                    "markup", "ng-version=\"" + ng.group(1) + "\"");
        } else if (lower.contains("<app-root") || lower.contains("_ngcontent")
                || lower.contains("_nghost")) {
            // Angular's bootstrap shell: the <app-root> custom element (and the
            // _nghost/_ngcontent attributes) sit in the raw HTML even though the
            // ng-version attribute is only written into the DOM once the app boots.
            // Detecting it here matches what browser fingerprinters see rendered.
            String marker = lower.contains("<app-root") ? "<app-root> element"
                    : "_nghost/_ngcontent attribute";
            add(found, Category.JS_FRAMEWORK, "Angular", "", Confidence.MEDIUM,
                    "markup", "Angular " + marker + " in HTML");
        }
        if (lower.contains("data-reactroot") || lower.contains("__react")
                || lower.contains("_reactrootcontainer")) {
            add(found, Category.JS_FRAMEWORK, "React", "", Confidence.MEDIUM,
                    "markup", "React DOM marker in HTML");
        }
        if (lower.contains("data-v-") || lower.contains("__vue__")
                || lower.contains("id=\"__nuxt\"") || lower.contains("id=\"app\" data-server-rendered")) {
            add(found, Category.JS_FRAMEWORK, "Vue.js", "", Confidence.MEDIUM,
                    "markup", "Vue marker in HTML");
        }
        if (lower.contains("__next_data__") || lower.contains("/_next/")) {
            add(found, Category.JS_FRAMEWORK, "Next.js", "", Confidence.HIGH,
                    "markup", "Next.js runtime marker");
        }
        if (lower.contains("/_nuxt/")) {
            add(found, Category.JS_FRAMEWORK, "Nuxt.js", "", Confidence.HIGH,
                    "markup", "Nuxt asset path");
        }
    }

    // ---- script / stylesheet URLs ---------------------------------------

    private void detectFromUrls(String pageUrl, String html, Map<String, Detection> found) {
        List<String> urls = new ArrayList<>();
        if (pageUrl != null) urls.add(pageUrl);
        if (html != null) {
            Matcher src = Pattern.compile("(?:src|href)=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(html);
            while (src.find()) urls.add(src.group(1));
        }
        for (String u : urls) {
            String lu = u.toLowerCase(Locale.ROOT);
            urlLib(lu, u, "react", Category.JS_LIBRARY, "React", found);
            urlLib(lu, u, "vue", Category.JS_FRAMEWORK, "Vue.js", found);
            urlLib(lu, u, "angular", Category.JS_FRAMEWORK, "Angular", found);
            urlLib(lu, u, "jquery", Category.JS_LIBRARY, "jQuery", found);
            urlLib(lu, u, "bootstrap", Category.UI_FRAMEWORK, "Bootstrap", found);
            urlLib(lu, u, "d3", Category.JS_LIBRARY, "D3.js", found);
            urlLib(lu, u, "lodash", Category.JS_LIBRARY, "Lodash", found);
            if (lu.contains("/wp-content/") || lu.contains("/wp-includes/")) {
                add(found, Category.CMS, "WordPress", "", Confidence.HIGH,
                        "script URL", u);
            }
            if (lu.contains("google-analytics.com") || lu.contains("gtag/js")) {
                add(found, Category.ANALYTICS, "Google Analytics", "", Confidence.HIGH,
                        "script URL", u);
            }
            if (lu.contains("googletagmanager.com")) {
                add(found, Category.ANALYTICS, "Google Tag Manager", "", Confidence.HIGH,
                        "script URL", u);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------

    private void urlLib(String lower, String raw, String needle, Category cat, String name,
                        Map<String, Detection> found) {
        if (!lower.contains(needle)) return;
        // Match a version adjacent to the library name in the filename.
        String version = "";
        Matcher m = Pattern.compile(Pattern.quote(needle)
                + "[-.@/]?v?([0-9]+(?:\\.[0-9]+){0,3})").matcher(lower);
        if (m.find()) version = m.group(1);
        add(found, cat, name, version,
                version.isEmpty() ? Confidence.MEDIUM : Confidence.HIGH, "script URL", raw);
    }

    private void matchBanner(String banner, String needle, Category cat, String name,
                             Map<String, Detection> found) {
        String lower = banner.toLowerCase(Locale.ROOT);
        int i = lower.indexOf(needle);
        if (i < 0) return;
        String version = "";
        Matcher m = Pattern.compile(Pattern.quote(needle)
                + "[/ ]?v?([0-9]+(?:\\.[0-9]+){0,3})").matcher(lower);
        if (m.find()) version = m.group(1);
        add(found, cat, name, version, Confidence.HIGH, "HTTP header", banner);
    }

    private void cookieMarker(String cookieName, String needle, Category cat, String name,
                              Map<String, Detection> found) {
        if (cookieName.equals(needle)) {
            add(found, cat, name, "", Confidence.HIGH, "cookie", cookieName);
        }
    }

    private void addIfHeader(List<String> headers, String headerName, Category cat, String fixedName,
                             Map<String, Detection> found, boolean versionFromValue) {
        String v = header(headers, headerName);
        if (v.isEmpty()) return;
        String name = fixedName != null ? fixedName : v.split("\\s")[0];
        String version = versionFromValue ? firstVersion(v) : "";
        add(found, cat, name, version, Confidence.HIGH, "HTTP header", headerName + ": " + v);
    }

    private void add(Map<String, Detection> found, Category cat, String name, String version,
                     Confidence confidence, String source, String detail) {
        if (name == null || name.isBlank()) return;
        String key = name.toLowerCase(Locale.ROOT);
        Detection existing = found.get(key);
        // Keep the detection with a version, or the higher confidence.
        if (existing != null) {
            boolean better = (!version.isEmpty() && existing.version.isEmpty())
                    || confidence.ordinal() > existing.confidence.ordinal();
            if (!better) return;
        }
        found.put(key, new Detection(cat, name, version, confidence, source, detail));
    }

    private static String header(List<String> headers, String name) {
        if (headers == null) return "";
        String want = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : headers) {
            if (line != null && line.toLowerCase(Locale.ROOT).startsWith(want)) {
                int colon = line.indexOf(':');
                return colon >= 0 ? line.substring(colon + 1).trim() : "";
            }
        }
        return "";
    }

    private static final Pattern VERSION = Pattern.compile("([0-9]+(?:\\.[0-9]+){0,3})");

    private static String firstVersion(String s) {
        Matcher m = VERSION.matcher(s);
        return m.find() ? m.group(1) : "";
    }
}
