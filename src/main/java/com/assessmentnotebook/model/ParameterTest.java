package com.assessmentnotebook.model;

/**
 * The recorded result of one probe against one parameter (spec §16, §18). It
 * captures the modification, the response, and what was observed, and it links
 * back to the page/parameter/request so it becomes permanent project knowledge.
 *
 * <p>Classification is deliberately conservative: a probe result is an
 * <b>observed behavior</b> or at most a <b>potential issue</b>; only the tester
 * promotes it to a confirmed finding (spec §16).
 */
public class ParameterTest {
    public enum Classification {
        OBSERVED_BEHAVIOR("Observed behavior"),
        POTENTIAL_ISSUE("Potential issue"),
        CONFIRMED_VULNERABILITY("Confirmed vulnerability");
        public final String label;
        Classification(String label) { this.label = label; }
    }

    /** Where the parameter lives in the request. */
    public enum Source { QUERY, BODY, JSON }

    public String id;
    public String pageId;
    public String formId;
    public String parameterId;
    public String variantId;
    public String requestId;

    public String parameterName = "";
    public Source source = Source.QUERY;

    public String probeKind = "";
    public String probeLabel = "";
    /** The value sent, or "(omitted)" when the parameter was removed. */
    public String sentValue = "";

    public int baselineStatus;
    public int baselineLength;
    public int responseStatus;
    public int responseLength;
    public boolean reflected;
    /** A known SQL-error fingerprint appeared in the response body. */
    public boolean sqlErrorSignature;

    public String observation = "";
    public Classification classification = Classification.OBSERVED_BEHAVIOR;
    public String notes = "";
    public String timestamp;
    /** Set when promoted; id of the created finding. */
    public String promotedVulnId;
}
