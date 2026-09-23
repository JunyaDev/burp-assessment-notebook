package com.assessmentnotebook.analyze;

import com.assessmentnotebook.model.Reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds where a submitted value is echoed back in a response and, for each
 * occurrence, records the syntactic context (HTML text, attribute, script,
 * header, ...). It deliberately does not judge whether a reflection is
 * exploitable: it records the observation so the tester can classify it.
 */
public final class ReflectionDetector {

    private static final Pattern SCRIPT_BLOCK =
            Pattern.compile("<script\\b[^>]*>.*?</script>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * @param body        response body (may be null)
     * @param headerLines response headers as "Name: value" lines (may be null)
     * @param contentType response content type (used to spot JSON contexts)
     * @param value       the value that was submitted and is being searched for
     */
    public List<Reflection> detect(String body, List<String> headerLines,
                                   String contentType, String value) {
        List<Reflection> out = new ArrayList<>();
        if (value == null || value.isEmpty()) return out;

        if (headerLines != null) {
            for (String line : headerLines) {
                if (line != null && line.contains(value)) {
                    Reflection r = base(value);
                    r.context = Reflection.Context.HTTP_HEADER;
                    int colon = line.indexOf(':');
                    r.location = colon > 0 ? line.substring(0, colon).trim() + " header" : "response header";
                    r.excerpt = trim(line);
                    out.add(r);
                }
            }
        }

        if (body == null || body.isEmpty()) return out;

        boolean json = contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("json");

        int[] scriptRanges = scriptRanges(body);

        int from = 0;
        while (true) {
            int idx = body.indexOf(value, from);
            if (idx < 0) break;
            Reflection r = base(value);
            r.location = "response body";
            r.excerpt = trim(body.substring(Math.max(0, idx - 40),
                    Math.min(body.length(), idx + value.length() + 40)));
            r.context = classify(body, idx, scriptRanges, json);
            if (!containsSame(out, r)) out.add(r);
            from = idx + value.length();
        }
        return out;
    }

    private Reflection.Context classify(String body, int idx, int[] scriptRanges, boolean json) {
        if (json) return Reflection.Context.JSON;
        for (int i = 0; i + 1 < scriptRanges.length; i += 2) {
            if (idx >= scriptRanges[i] && idx < scriptRanges[i + 1]) {
                return Reflection.Context.JAVASCRIPT;
            }
        }
        // Inside a tag if the nearest '<' before us is after the nearest '>'.
        int lt = body.lastIndexOf('<', idx);
        int gt = body.lastIndexOf('>', idx);
        if (lt > gt) return Reflection.Context.HTML_ATTRIBUTE;
        return Reflection.Context.HTML_TEXT;
    }

    private static int[] scriptRanges(String body) {
        List<Integer> bounds = new ArrayList<>();
        Matcher m = SCRIPT_BLOCK.matcher(body);
        while (m.find()) {
            bounds.add(m.start());
            bounds.add(m.end());
        }
        int[] arr = new int[bounds.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = bounds.get(i);
        return arr;
    }

    private static boolean containsSame(List<Reflection> list, Reflection r) {
        for (Reflection e : list) {
            if (e.context == r.context && e.excerpt.equals(r.excerpt)
                    && e.location.equals(r.location)) return true;
        }
        return false;
    }

    private static Reflection base(String value) {
        Reflection r = new Reflection();
        r.submittedValue = value;
        return r;
    }

    private static String trim(String s) {
        String collapsed = s.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 160 ? collapsed.substring(0, 160) + "…" : collapsed;
    }
}
