package com.assessmentnotebook.model;

/**
 * A UI action a tester performed on a page and the behavior it produced:
 * opening a menu, expanding an accordion, submitting a form, triggering an XHR.
 * Interactions capture dynamic behavior that the initial HTML does not show.
 *
 * <p>An interaction may reference the request it generated and a screenshot of
 * the resulting state, wiring up the Page -> Interaction -> Request -> Response
 * -> observed-state chain the report later relies on.
 */
public class Interaction {
    public String id;
    public String pageId;
    /** What the tester did, e.g. "Clicked the profile dropdown". */
    public String action = "";
    /** What was observed as a result. */
    public String observedBehavior = "";
    /** Id of a request this interaction caused, if any. */
    public String requestId;
    /** Id of a screenshot showing the resulting state, if any. */
    public String screenshotId;
    public String notes = "";
    public String createdAt;
}
