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
}
