package com.assessmentnotebook.analyze;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

/**
 * Cheap, stable descriptors of a response body used to compare page variants
 * (spec §13): the title, and a structural signature (the sequence of element
 * tags) that changes when the page's shape changes but is insensitive to the
 * specific text/values inside it. Comparing signatures answers "did the
 * structure change?" separately from "did the text change?".
 */
public final class ResponseShape {
    private ResponseShape() {}

    public static String title(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            return Jsoup.parse(body).title();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** A signature of the element-tag sequence; equal shapes give equal strings. */
    public static String structureSignature(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            Document doc = Jsoup.parse(body);
            Elements all = doc.getAllElements();
            StringBuilder b = new StringBuilder();
            for (org.jsoup.nodes.Element e : all) {
                b.append(e.tagName()).append('>');
            }
            return Integer.toHexString(b.toString().hashCode());
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Bodies above this size are not parsed for a shape; they compare as "unknown". */
    private static final int MAX_SHAPE_BODY = 2_000_000;
    /** A response documents at most this many field paths. */
    private static final int MAX_FIELDS = 400;

    /**
     * The distinct field paths of a JSON body, sorted: array positions become
     * {@code []} and id-like keys {@code {id}}, so a list of 3 records and a
     * list of 300 have the same fields. Empty when the body is not JSON.
     */
    public static java.util.List<String> jsonFields(String body) {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        if (body == null || body.length() > MAX_SHAPE_BODY) return new java.util.ArrayList<>();
        String t = body.stripLeading();
        // Gson is lenient enough to "parse" bare words; only objects and arrays count.
        if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) {
            return new java.util.ArrayList<>();
        }
        try {
            walkJson("", com.google.gson.JsonParser.parseString(t), out);
        } catch (RuntimeException notJson) {
            return new java.util.ArrayList<>();
        }
        return new java.util.ArrayList<>(out);
    }

    private static void walkJson(String prefix, com.google.gson.JsonElement el,
            java.util.Set<String> out) {
        if (out.size() >= MAX_FIELDS) return;
        if (el != null && el.isJsonObject()) {
            if (!prefix.isEmpty()) out.add(prefix);
            for (var e : el.getAsJsonObject().entrySet()) {
                String key = UrlTemplates.looksLikeId(e.getKey()) ? UrlTemplates.ID : e.getKey();
                walkJson(prefix.isEmpty() ? key : prefix + "." + key, e.getValue(), out);
            }
        } else if (el != null && el.isJsonArray()) {
            String item = prefix + "[]";
            // The array itself is a field even when empty; "[]" marks a top-level array.
            out.add(prefix.isEmpty() ? item : prefix);
            for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
                walkJson(item, child, out);
            }
        } else if (!prefix.isEmpty() && !prefix.endsWith("[]")) {
            // A plain value inside an array adds nothing beyond the array itself.
            out.add(prefix);
        }
    }

    /**
     * A fingerprint of what a response is <i>made of</i>, insensitive to how
     * much of it there is: the set of JSON field paths, or for HTML the set of
     * distinct element paths (with the names of form controls). Ten search
     * results and twelve share a shape; a results table replaced by an error
     * panel, or a form that gained a field, does not. Blank when there is no
     * structure to read (empty, binary or oversized bodies).
     */
    public static String shapeKey(String body, String contentType) {
        if (body == null || body.isBlank() || body.length() > MAX_SHAPE_BODY) return "";
        java.util.List<String> fields = jsonFields(body);
        if (!fields.isEmpty()) return hash("json", fields);
        String ct = contentType == null ? "" : contentType.toLowerCase();
        boolean html = ct.contains("html") || (ct.isBlank() && body.stripLeading().startsWith("<"));
        if (!html) return "";
        try {
            java.util.TreeSet<String> paths = new java.util.TreeSet<>();
            walkHtml("", Jsoup.parse(body).body(), paths);
            return hash("html", paths);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static void walkHtml(String prefix, org.jsoup.nodes.Element el,
            java.util.Set<String> out) {
        if (el == null) return;
        String tag = el.tagName();
        String name = el.attr("name");
        boolean control = tag.equals("input") || tag.equals("select") || tag.equals("textarea")
                || tag.equals("button");
        String path = prefix + ">" + tag + (control && !name.isEmpty() ? "[" + name + "]" : "");
        out.add(path);
        for (org.jsoup.nodes.Element child : el.children()) walkHtml(path, child, out);
    }

    private static String hash(String kind, java.util.Collection<String> parts) {
        return kind + ":" + Integer.toHexString(String.join("\n", parts).hashCode());
    }
}
