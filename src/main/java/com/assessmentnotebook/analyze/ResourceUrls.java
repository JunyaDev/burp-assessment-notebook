package com.assessmentnotebook.analyze;

import java.net.URI;
import java.util.Locale;

/**
 * Canonicalizes a resource URL so the same underlying file or endpoint has a
 * single, stable identity no matter how it was discovered. This is what lets a
 * script loaded by several pages be documented once (spec §11) instead of
 * duplicated per discovery path.
 *
 * <p>Rules:
 * <ul>
 *   <li>scheme and host are lower-cased; the default port for the scheme is
 *       dropped;</li>
 *   <li>the fragment is always dropped (it never identifies a distinct
 *       resource);</li>
 *   <li>for <b>static assets</b> (js, css, images, fonts, maps) the query is
 *       dropped too, because it is almost always a cache-buster
 *       ({@code app.js?v=9f3a}); for everything else (API/XHR/other) the query
 *       is kept, because it distinguishes endpoints
 *       ({@code /api/users?role=admin}).</li>
 * </ul>
 * The transformation is best-effort: an unparseable URL is returned trimmed but
 * otherwise unchanged, so nothing is ever lost.
 */
public final class ResourceUrls {
    private ResourceUrls() {}

    /** Canonical identity for a resource URL. Never returns null. */
    public static String canonical(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return "";
        try {
            URI u = URI.create(trimmed);
            if (u.getScheme() == null || u.getHost() == null) {
                return stripFragment(trimmed);
            }
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost().toLowerCase(Locale.ROOT);
            int port = u.getPort();
            if (isDefaultPort(scheme, port)) port = -1;

            String path = u.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            // Collapse a trailing slash on a non-root path so "/a/" == "/a".
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            String query = u.getRawQuery();
            boolean keepQuery = query != null && !isStaticAsset(path);

            StringBuilder b = new StringBuilder();
            b.append(scheme).append("://").append(host);
            if (port >= 0) b.append(':').append(port);
            b.append(path);
            if (keepQuery) b.append('?').append(query);
            return b.toString();
        } catch (RuntimeException notAUri) {
            return stripFragment(trimmed);
        }
    }

    /** True when two URLs denote the same canonical resource. */
    public static boolean sameResource(String a, String b) {
        return canonical(a).equals(canonical(b));
    }

    /** Whether the path names a static asset whose query is a cache-buster. */
    public static boolean isStaticAsset(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        int dot = p.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = p.substring(dot + 1);
        switch (ext) {
            case "js": case "mjs": case "cjs":
            case "css":
            case "png": case "jpg": case "jpeg": case "gif": case "svg":
            case "webp": case "ico": case "bmp":
            case "woff": case "woff2": case "ttf": case "otf": case "eot":
            case "map":
                return true;
            default:
                return false;
        }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
    }

    private static String stripFragment(String s) {
        int h = s.indexOf('#');
        return h >= 0 ? s.substring(0, h) : s;
    }
}
