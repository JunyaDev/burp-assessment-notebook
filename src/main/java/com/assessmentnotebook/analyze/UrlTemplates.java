package com.assessmentnotebook.analyze;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Splits URLs and derives the identity auto-capture uses to decide whether it
 * has seen a page before: scheme, host and path, with the query ignored and
 * (optionally) id-like path segments collapsed to one placeholder, so
 * {@code /api/users/17} and {@code /api/users/42?full=1} are the same endpoint.
 *
 * <p>The parsing is deliberately tolerant. URLs taken from live traffic often
 * contain characters {@link java.net.URI} rejects ({@code |}, <code>{</code>,
 * unencoded spaces), and a URL that cannot be classified is a URL that is
 * silently never captured.
 */
public final class UrlTemplates {
    private UrlTemplates() {}

    /** Placeholder that stands in for an id-like path segment or JSON key. */
    public static final String ID = "{id}";

    private static final Pattern UUID = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern LONG_HEX = Pattern.compile("(?i)[0-9a-f]{16,}");

    /**
     * True for a value that names one record rather than a kind of thing: a
     * number, a UUID, or a long hex string (hash, ObjectId).
     */
    public static boolean looksLikeId(String s) {
        if (s == null || s.isEmpty()) return false;
        boolean digits = true;
        boolean anyDigit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') anyDigit = true; else digits = false;
        }
        if (digits) return true;
        if (UUID.matcher(s).matches()) return true;
        return anyDigit && LONG_HEX.matcher(s).matches();
    }

    /**
     * Identity of the page behind a URL: {@code scheme://host[:port]/path},
     * host lower-cased, default port, query, fragment and a trailing slash
     * dropped; id-like segments replaced by {@link #ID} when requested.
     */
    public static String key(String url, boolean collapseIds) {
        if (url == null) return "";
        String u = url.trim();
        int schemeEnd = u.indexOf("://");
        if (schemeEnd <= 0) return normalizePath(pathOnly(u), collapseIds);
        String scheme = u.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        String authority = authority(u, schemeEnd + 3);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        authority = authority.toLowerCase(Locale.ROOT);
        if (("http".equals(scheme) && authority.endsWith(":80"))
                || ("https".equals(scheme) && authority.endsWith(":443"))) {
            authority = authority.substring(0, authority.lastIndexOf(':'));
        }
        return scheme + "://" + authority + normalizePath(path(u), collapseIds);
    }

    /**
     * The path with id-like segments collapsed, or "" when the path has none
     * (so callers can tell "nothing to template" from a templated path).
     */
    public static String templatePath(String url) {
        String plain = normalizePath(path(url), false);
        String templated = normalizePath(path(url), true);
        return templated.equals(plain) ? "" : templated;
    }

    /** Host without port or user-info, lower-cased; "" when the URL has none. */
    public static String host(String url) {
        if (url == null) return "";
        String u = url.trim();
        int schemeEnd = u.indexOf("://");
        if (schemeEnd <= 0) return "";
        String authority = authority(u, schemeEnd + 3);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        int colon = authority.lastIndexOf(':');
        // A colon inside an IPv6 literal ([::1]) is not a port separator.
        if (colon >= 0 && authority.indexOf(']') < colon) authority = authority.substring(0, colon);
        return authority.toLowerCase(Locale.ROOT);
    }

    /** Raw path, never empty ("/" for a bare origin); query and fragment excluded. */
    public static String path(String url) {
        if (url == null) return "/";
        String u = url.trim();
        int schemeEnd = u.indexOf("://");
        if (schemeEnd <= 0) return pathOnly(u);
        int start = schemeEnd + 3;
        int slash = indexOfAny(u, start, "/?#");
        if (slash < 0 || u.charAt(slash) != '/') return "/";
        int end = indexOfAny(u, slash, "?#");
        return end < 0 ? u.substring(slash) : u.substring(slash, end);
    }

    /** Raw query without the leading '?', or "" when there is none. */
    public static String query(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        if (q < 0) return "";
        int hash = url.indexOf('#', q);
        return hash < 0 ? url.substring(q + 1) : url.substring(q + 1, hash);
    }

    private static String authority(String u, int start) {
        int end = indexOfAny(u, start, "/?#");
        return end < 0 ? u.substring(start) : u.substring(start, end);
    }

    /** Path of a string that has no scheme (already a path, possibly with a query). */
    private static String pathOnly(String u) {
        int end = indexOfAny(u, 0, "?#");
        String p = end < 0 ? u : u.substring(0, end);
        return p.isEmpty() ? "/" : p;
    }

    /** Rebuild the path from its non-empty segments, collapsing ids if asked. */
    private static String normalizePath(String path, boolean collapseIds) {
        StringBuilder b = new StringBuilder();
        for (String seg : (path == null ? "" : path).split("/")) {
            if (seg.isEmpty()) continue;
            b.append('/').append(collapseIds && looksLikeId(seg) ? ID : seg);
        }
        return b.length() == 0 ? "/" : b.toString();
    }

    private static int indexOfAny(String s, int from, String chars) {
        for (int i = from; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }
}
