package com.assessmentnotebook.model;

/**
 * An image capturing a page in a particular visual state. A page may own many
 * screenshots representing sequential interactive states (default, menu open,
 * dialog shown, ...) which the generated page document steps through in order.
 */
public class Screenshot {
    public String id;
    public String pageId;
    /** Path to the image file, relative to the project root. */
    public String imageFile = "";
    /** Label for this state, e.g. "Navigation opened". */
    public String stateDescription = "";
    /** Ordering within the page's sequence of states (ascending). */
    public int sequence;
    /** Id of the interaction that produced this state, if any. */
    public String relatedInteractionId;
    /** Id of a related request, if any. */
    public String relatedRequestId;
    public String timestamp;
    public String notes = "";
}
