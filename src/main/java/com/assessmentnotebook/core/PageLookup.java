package com.assessmentnotebook.core;

import com.assessmentnotebook.analyze.ResourceUrls;
import com.assessmentnotebook.analyze.UrlTemplates;
import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.Project;

import java.util.HashMap;
import java.util.Map;

/**
 * Finds the registered page a URL refers to, the way a link, a redirect or a
 * Referer means it: by exact URL first, then ignoring the differences that do
 * not change the destination (default port, fragment, trailing slash, host
 * case), and finally by path template for pages that document every id at a
 * path ({@code /users/42} belongs to the page registered as {@code /users/{id}}).
 *
 * <p>When several pages share a URL the GET one wins, since following a link is
 * a GET. This is a snapshot: build a new lookup after pages are added.
 */
public final class PageLookup {
    private final Map<String, Page> exact = new HashMap<>();
    private final Map<String, Page> canonical = new HashMap<>();
    private final Map<String, Page> templated = new HashMap<>();

    public PageLookup(Project project) {
        for (int pass = 0; pass < 2; pass++) {
            for (Page p : project.pages) {
                boolean get = "GET".equalsIgnoreCase(p.method);
                if (get != (pass == 0) || p.url == null || p.url.isBlank()) continue;
                exact.putIfAbsent(p.url, p);
                canonical.putIfAbsent(ResourceUrls.canonical(p.url), p);
                if (p.pathTemplate != null && !p.pathTemplate.isBlank()) {
                    templated.putIfAbsent(UrlTemplates.key(p.url, true), p);
                }
            }
        }
    }

    /** The page {@code url} leads to, or null when none is registered. */
    public Page find(String url) {
        if (url == null || url.isBlank()) return null;
        Page p = exact.get(url);
        if (p == null) p = canonical.get(ResourceUrls.canonical(url));
        if (p == null && !templated.isEmpty()) p = templated.get(UrlTemplates.key(url, true));
        return p;
    }

    /** Like {@link #find}, but never by path template: the URL itself must be registered. */
    public Page findSameUrl(String url) {
        if (url == null || url.isBlank()) return null;
        Page p = exact.get(url);
        return p != null ? p : canonical.get(ResourceUrls.canonical(url));
    }
}
