package com.assessmentnotebook.core;

import com.assessmentnotebook.analyze.JsonParameters;
import com.assessmentnotebook.analyze.UrlTemplates;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The inputs a request carried, read from a {@link PageRegistration} without
 * Burp: query parameters, url-encoded body fields, flattened JSON body fields,
 * cookies and whether it was authenticated. Auto-capture uses this both to tell
 * two captures of a page apart and to describe a variant's request conditions.
 */
public final class RequestParams {
    public final Map<String, String> query = new LinkedHashMap<>();
    public final Map<String, String> body = new LinkedHashMap<>();
    public final Map<String, String> json = new LinkedHashMap<>();
    public final Map<String, String> cookies = new LinkedHashMap<>();
    public String contentType = "";
    public boolean authorized;

    public static RequestParams of(PageRegistration reg) {
        RequestParams p = new RequestParams();
        parsePairs(UrlTemplates.query(reg.url), "&", p.query);
        p.contentType = header(reg.requestHeaders, "content-type");
        String ct = p.contentType.toLowerCase(Locale.ROOT);
        String body = reg.requestBody == null ? "" : reg.requestBody;
        if (ct.contains("json")) {
            p.json.putAll(JsonParameters.flatten(body));
        } else if (ct.contains("x-www-form-urlencoded")) {
            parsePairs(body, "&", p.body);
        }
        parsePairs(header(reg.requestHeaders, "cookie"), ";", p.cookies);
        p.authorized = !header(reg.requestHeaders, "authorization").isEmpty();
        return p;
    }

    /** Query, body and JSON inputs in one map (the names a form documents). */
    public Map<String, String> all() {
        Map<String, String> all = new LinkedHashMap<>(query);
        all.putAll(body);
        all.putAll(json);
        return all;
    }

    private static void parsePairs(String text, String separator, Map<String, String> out) {
        if (text == null || text.isBlank()) return;
        for (String pair : text.split(separator)) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq)).trim();
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            if (!name.isEmpty()) out.putIfAbsent(name, value);
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (RuntimeException malformed) {
            return s;
        }
    }

    static String header(List<String> headers, String name) {
        if (headers == null) return "";
        String want = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : headers) {
            if (line != null && line.toLowerCase(Locale.ROOT).startsWith(want)) {
                return line.substring(want.length()).trim();
            }
        }
        return "";
    }
}
