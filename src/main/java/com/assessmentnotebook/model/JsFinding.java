package com.assessmentnotebook.model;

/**
 * A piece of interesting data discovered by scanning a JavaScript resource
 * (e.g. Flutter's compiled {@code main.dart.js}): an endpoint, a hard-coded
 * secret, a dangerous sink, an exported function, or an auth-bypass hint. Each
 * finding is tied to the resource it came from, and the resource is in turn
 * linked to the pages that load it, so findings can be listed by page.
 */
public class JsFinding {
    /** What kind of interesting thing this is. */
    public enum Kind {
        ENDPOINT("Endpoint / URL"),
        SECRET("Secret / token / password"),
        DANGEROUS_CALL("Dangerous call (eval/exec/…)"),
        EXPORTED_FUNCTION("Exported function"),
        BYPASS("Bypass / debug flag"),
        INTERESTING("Other interesting string");

        public final String label;
        Kind(String label) { this.label = label; }
    }

    public String id;
    public Kind kind = Kind.INTERESTING;
    /** The matched text (truncated for very long matches). */
    public String value = "";
    /** Why it matched — the rule/pattern name. */
    public String detail = "";
    /** A short surrounding excerpt for context. */
    public String context = "";
    /** The JS resource this was found in. */
    public String resourceId;
    /** 1-based line in the source, best-effort (0 when unknown). */
    public int lineNumber;
    public String notes = "";
    public String timestamp;
}
