package com.assessmentnotebook.model;

/**
 * One auto-capture rule: a set of conditions on a request/response and what to
 * register when they all hold. Rules are evaluated top to bottom and the first
 * enabled match decides, so an {@link Action#IGNORE} rule placed above a broad
 * one carves an exception out of it.
 *
 * <p>An empty condition means "any". The matching itself lives in
 * {@code RuleMatcher}; this class is only the stored shape.
 */
public class CaptureRule {
    /** What a matching exchange is registered as. */
    public enum Action {
        /** Let the traffic classifier decide between page, API endpoint and resource. */
        AUTO("Auto-detect"),
        PAGE("Page"),
        API("API endpoint"),
        RESOURCE("Resource"),
        /** Matching traffic is never registered (and later rules are not consulted). */
        IGNORE("Ignore");

        public final String label;
        Action(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public String name = "";
    public boolean enabled = true;

    /** Comma-separated host globs ({@code example.com, *.example.com}), case-insensitive. */
    public String host = "";
    /** Path glob ({@code /api/*}), or {@code re:<regex>} searched in path?query. */
    public String path = "";
    /** Comma-separated methods ({@code GET,POST}). */
    public String methods = "";
    /** Comma-separated substrings of the response Content-Type; any one suffices. */
    public String contentType = "";
    /** Comma-separated status codes, classes or ranges ({@code 200,3xx,400-404}). */
    public String status = "";
    /** Only traffic Burp's target scope includes. */
    public boolean inScopeOnly = false;

    /** Which Burp tools the rule listens to. */
    public boolean fromProxy = true;
    public boolean fromRepeater = false;

    public Action action = Action.AUTO;

    public CaptureRule copy() {
        CaptureRule r = new CaptureRule();
        r.name = name;
        r.enabled = enabled;
        r.host = host;
        r.path = path;
        r.methods = methods;
        r.contentType = contentType;
        r.status = status;
        r.inScopeOnly = inScopeOnly;
        r.fromProxy = fromProxy;
        r.fromRepeater = fromRepeater;
        r.action = action;
        return r;
    }
}
