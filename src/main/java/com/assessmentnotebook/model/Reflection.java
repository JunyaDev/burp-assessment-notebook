package com.assessmentnotebook.model;

/**
 * A single observed reflection of a submitted value in a response.
 *
 * <p>Reflections are recorded as observations, never auto-classified as
 * vulnerabilities: the tester decides separately whether a reflection is
 * exploitable. Each reflection captures where the value landed and in what
 * syntactic context, since the context determines what (if anything) it means.
 */
public class Reflection {
    /** Syntactic context in which the value appeared in the response. */
    public enum Context {
        HTML_TEXT("HTML text"),
        HTML_ATTRIBUTE("HTML attribute"),
        JAVASCRIPT("JavaScript"),
        JSON("JSON"),
        URL("URL"),
        HTTP_HEADER("HTTP header"),
        DOM_GENERATED("DOM-generated content"),
        OTHER("Other");

        public final String label;
        Context(String label) { this.label = label; }
    }

    /** The value that was submitted (e.g. a canary like {@code TEST123}). */
    public String submittedValue = "";
    /** A short excerpt of the response showing the reflection in situ. */
    public String excerpt = "";
    public Context context = Context.OTHER;
    /** Where the reflection was seen: "response body", "Location header", etc. */
    public String location = "";
    public String notes = "";
}
