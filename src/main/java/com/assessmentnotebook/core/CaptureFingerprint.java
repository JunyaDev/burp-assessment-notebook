package com.assessmentnotebook.core;

import com.assessmentnotebook.analyze.ResponseShape;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * What auto-capture compares to decide whether a new sighting of a known page
 * is worth recording. Two captures are "the same" when they agree on all of:
 * the status code, the <i>names</i> of the request parameters, whether the
 * request was authenticated, and the response's structural shape.
 *
 * <p>Values are deliberately left out. A different search term, record id or
 * CSRF token is the same page; a new parameter, a 403 instead of a 200, or a
 * response that gained a field is a variant.
 */
public final class CaptureFingerprint {
    public final int status;
    /** Sorted, comma-joined parameter names, prefixed q: / b: / j: by location. */
    public final String params;
    public final boolean authorized;
    public final String shape;

    private CaptureFingerprint(int status, String params, boolean authorized, String shape) {
        this.status = status;
        this.params = params;
        this.authorized = authorized;
        this.shape = shape;
    }

    public static CaptureFingerprint of(PageRegistration reg) {
        RequestParams rp = RequestParams.of(reg);
        TreeSet<String> names = new TreeSet<>();
        for (String n : rp.query.keySet()) names.add("q:" + safe(n));
        for (String n : rp.body.keySet()) names.add("b:" + safe(n));
        // items[0].sku and items[1].sku are one input, not two.
        for (String n : rp.json.keySet()) names.add("j:" + safe(n.replaceAll("\\[\\d+]", "[]")));
        return new CaptureFingerprint(reg.statusCode, String.join(",", names), rp.authorized,
                ResponseShape.shapeKey(reg.pageSource, reg.contentType));
    }

    /** The stored form: {@code status|params|auth|shape}. */
    public String key() {
        return status + "|" + params + "|" + (authorized ? "auth" : "anon") + "|" + shape;
    }

    /**
     * Plain-language differences between a baseline key and another, e.g.
     * "status 200 → 403" or "new parameter debug". Empty when they are equal.
     */
    public static List<String> differences(String baseKey, String key) {
        List<String> out = new ArrayList<>();
        String[] a = split(baseKey);
        String[] b = split(key);
        if (!a[0].equals(b[0])) out.add("status " + a[0] + " → " + b[0]);
        List<String> before = names(a[1]);
        List<String> after = names(b[1]);
        for (String n : after) if (!before.contains(n)) out.add("new parameter " + bare(n));
        for (String n : before) if (!after.contains(n)) out.add("without parameter " + bare(n));
        if (!a[2].equals(b[2])) out.add("auth".equals(b[2]) ? "authenticated" : "unauthenticated");
        if (!a[3].equals(b[3])) out.add("response structure changed");
        return out;
    }

    /** A short variant label built from {@link #differences}. */
    public static String label(String baseKey, String key) {
        List<String> diffs = differences(baseKey, key);
        if (diffs.isEmpty()) return "variant";
        String label = String.join(" · ", diffs);
        return label.length() > 90 ? label.substring(0, 89) + "…" : label;
    }

    private static String[] split(String key) {
        String[] parts = (key == null ? "" : key).split("\\|", 4);
        String[] out = {"", "", "", ""};
        System.arraycopy(parts, 0, out, 0, Math.min(parts.length, 4));
        return out;
    }

    private static List<String> names(String joined) {
        List<String> out = new ArrayList<>();
        for (String n : joined.split(",")) if (!n.isEmpty()) out.add(n);
        return out;
    }

    private static String bare(String prefixed) {
        return prefixed.length() > 2 ? prefixed.substring(2) : prefixed;
    }

    /** Keep the separators unambiguous whatever a parameter is called. */
    private static String safe(String name) {
        return name.replace("|", "%7C").replace(",", "%2C");
    }
}
