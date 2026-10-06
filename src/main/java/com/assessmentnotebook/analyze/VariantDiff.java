package com.assessmentnotebook.analyze;

import com.assessmentnotebook.model.PageVariant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares two page variants (spec §13) and reports, as structured lists, which
 * request inputs differ and which observable outputs changed. The goal is to
 * answer: <i>which inputs cause which behavior changes?</i> — so input and output
 * differences are reported separately and correlated by reflected inputs.
 */
public final class VariantDiff {
    private VariantDiff() {}

    public static final class Comparison {
        public final String baselineId;
        public final String variantId;
        public final List<String> inputDifferences = new ArrayList<>();
        public final List<String> outputDifferences = new ArrayList<>();
        public final List<String> reflectedInputs = new ArrayList<>();

        Comparison(String baselineId, String variantId) {
            this.baselineId = baselineId;
            this.variantId = variantId;
        }

        public boolean hasOutputChange() { return !outputDifferences.isEmpty(); }
    }

    /** Compare each variant after the first against the first (baseline). */
    public static List<Comparison> compareToBaseline(List<PageVariant> variantsOfPage) {
        List<Comparison> out = new ArrayList<>();
        if (variantsOfPage == null || variantsOfPage.size() < 2) return out;
        PageVariant base = variantsOfPage.get(0);
        for (int i = 1; i < variantsOfPage.size(); i++) {
            out.add(compare(base, variantsOfPage.get(i)));
        }
        return out;
    }

    public static Comparison compare(PageVariant base, PageVariant v) {
        Comparison c = new Comparison(base.id, v.id);
        diffMap(c.inputDifferences, "query", base.queryParams, v.queryParams);
        diffMap(c.inputDifferences, "body", base.bodyParams, v.bodyParams);
        diffMap(c.inputDifferences, "json", base.jsonParams, v.jsonParams);
        diffMap(c.inputDifferences, "cookie", base.cookies, v.cookies);
        if (!eq(base.authContext, v.authContext)) {
            c.inputDifferences.add("auth: " + show(base.authContext) + " -> " + show(v.authContext));
        }

        if (base.statusCode != v.statusCode) {
            c.outputDifferences.add("status: " + base.statusCode + " -> " + v.statusCode);
        }
        if (base.bodyLength != v.bodyLength) {
            c.outputDifferences.add("length: " + base.bodyLength + " -> " + v.bodyLength
                    + " (" + signed(v.bodyLength - base.bodyLength) + ")");
        }
        if (!eq(base.title, v.title)) {
            c.outputDifferences.add("title: " + show(base.title) + " -> " + show(v.title));
        }
        if (!base.responseFields.isEmpty() && !v.responseFields.isEmpty()) {
            // Both are JSON: name the fields instead of saying "something moved".
            fieldDiff(c.outputDifferences, "fields added", v.responseFields, base.responseFields);
            fieldDiff(c.outputDifferences, "fields removed", base.responseFields, v.responseFields);
        } else if (!eq(base.structureSignature, v.structureSignature)) {
            c.outputDifferences.add("page structure changed");
        }
        c.reflectedInputs.addAll(v.reflectedInputNames);
        return c;
    }

    private static void diffMap(List<String> out, String kind,
            Map<String, String> a, Map<String, String> b) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            String av = a.get(k);
            String bv = b.get(k);
            if (av == null) {
                out.add(kind + " " + k + ": (absent) -> " + show(bv));
            } else if (bv == null) {
                out.add(kind + " " + k + ": " + show(av) + " -> (absent)");
            } else if (!av.equals(bv)) {
                out.add(kind + " " + k + ": " + show(av) + " -> " + show(bv));
            }
        }
    }

    /** Report the fields in {@code in} that are missing from {@code notIn}. */
    private static void fieldDiff(List<String> out, String what, List<String> in,
            List<String> notIn) {
        List<String> only = new ArrayList<>(in);
        only.removeAll(notIn);
        if (only.isEmpty()) return;
        int shown = Math.min(only.size(), 12);
        out.add(what + ": " + String.join(", ", only.subList(0, shown))
                + (only.size() > shown ? " (+" + (only.size() - shown) + " more)" : ""));
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static String show(String s) {
        if (s == null || s.isEmpty()) return "(empty)";
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String signed(int n) {
        return n >= 0 ? "+" + n : String.valueOf(n);
    }
}
