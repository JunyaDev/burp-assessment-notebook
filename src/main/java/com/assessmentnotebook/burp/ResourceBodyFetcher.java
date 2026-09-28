package com.assessmentnotebook.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.assessmentnotebook.analyze.DiscoveredPage;

/**
 * Populates the bodies of a page's discovered resources when the tester ticks
 * "Capture resource bodies" in the Register Page dialog.
 *
 * <p>Strategy chosen by the user (2026-09-28): <b>history first, live fetch as a
 * fallback</b>. For each resource we first look for a response Burp already
 * holds in its site map (captured while the tester browsed the app), which sends
 * no new traffic. Only when a resource has never been seen do we issue a single
 * GET for it, so the notebook can still hold its body. Anything that fails is
 * left as identity-only; capture is best-effort and never blocks registration.
 *
 * <p>Not exercised by the headless test suite: it depends on the live Montoya
 * HTTP stack and Burp's site map. Keep the logic here thin and side-effect-free
 * apart from the optional outbound GET.
 */
public final class ResourceBodyFetcher {

    private final MontoyaApi api;

    public ResourceBodyFetcher(MontoyaApi api) {
        this.api = api;
    }

    /**
     * Fill {@code body}/{@code bodySource} for every resource on {@code page}
     * that has an absolute URL and no body yet. {@code allowFetch} gates the
     * live-GET fallback; when false, only site-map hits are used.
     */
    public void capture(DiscoveredPage page, boolean allowFetch) {
        if (page == null) return;
        for (DiscoveredPage.DiscoveredResource r : page.resources) {
            if (r.url == null || !r.url.startsWith("http")) continue;
            if (r.body != null && !r.body.isBlank()) continue;
            String fromHistory = bodyFromSiteMap(r.url);
            if (fromHistory != null) {
                r.body = fromHistory;
                r.bodySource = "proxy history";
            } else if (allowFetch) {
                String fetched = bodyFromFetch(r.url);
                if (fetched != null) {
                    r.body = fetched;
                    r.bodySource = "live fetch";
                }
            }
        }
    }

    /** The most recent captured response body for this exact URL, or null. */
    private String bodyFromSiteMap(String url) {
        try {
            String best = null;
            for (HttpRequestResponse rr : api.siteMap().requestResponses()) {
                if (rr == null || !rr.hasResponse() || rr.request() == null) continue;
                if (url.equals(rr.request().url())) {
                    best = rr.response().bodyToString(); // later entries overwrite earlier
                }
            }
            return best;
        } catch (RuntimeException e) {
            api.logging().logToError("Resource body lookup failed for " + url + ": " + e);
            return null;
        }
    }

    /** A single GET for the resource; null on any failure or empty body. */
    private String bodyFromFetch(String url) {
        try {
            HttpRequestResponse rr = api.http().sendRequest(HttpRequest.httpRequestFromUrl(url));
            if (rr == null || !rr.hasResponse()) return null;
            HttpResponse resp = rr.response();
            String body = resp.bodyToString();
            return body == null || body.isBlank() ? null : body;
        } catch (RuntimeException e) {
            api.logging().logToError("Resource fetch failed for " + url + ": " + e);
            return null;
        }
    }
}
