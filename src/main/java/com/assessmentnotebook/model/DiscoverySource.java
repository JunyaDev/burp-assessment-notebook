package com.assessmentnotebook.model;

/**
 * A controlled vocabulary for <b>how</b> a page, resource or element was found
 * (spec §3). Replacing the old free-text "discovery source" with a fixed set of
 * kinds makes the field mean something consistent across a project, while a
 * separate free-text detail (e.g. the referring URL) keeps the specifics.
 *
 * <p>The kind can be inferred from traffic (see {@code DiscoverySourceInference}
 * and the redirect detection in {@code NotebookController}) and is always
 * editable by the tester.
 */
public enum DiscoverySource {
    DIRECT_NAVIGATION("Direct navigation"),
    HTTP_REDIRECT("HTTP redirect"),
    LINK_FROM_PAGE("Link from another page"),
    FORM_SUBMISSION("Form submission"),
    JAVASCRIPT("JavaScript"),
    XHR_FETCH("XHR/fetch"),
    BURP_HISTORY("Burp HTTP history"),
    BURP_REPEATER("Burp Repeater"),
    MANUAL("Manual registration"),
    BROWSER_INTERACTION("Browser interaction"),
    SOURCE_CODE_REFERENCE("Source-code reference"),
    API_RESPONSE("API response"),
    OTHER_PAGE_OR_RESOURCE("Other page/resource"),
    OTHER("Other");

    public final String label;
    DiscoverySource(String label) { this.label = label; }

    @Override public String toString() { return label; }

    /** Parse a stored name back to a kind, tolerating legacy/blank values. */
    public static DiscoverySource parse(String s) {
        if (s == null) return OTHER;
        for (DiscoverySource d : values()) {
            if (d.name().equalsIgnoreCase(s.trim()) || d.label.equalsIgnoreCase(s.trim())) {
                return d;
            }
        }
        return OTHER;
    }
}
