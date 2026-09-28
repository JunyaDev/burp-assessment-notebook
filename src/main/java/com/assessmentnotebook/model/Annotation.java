package com.assessmentnotebook.model;

/**
 * A rectangle drawn on a screenshot to call out an element (spec §9). Stored
 * with the screenshot and rendered as a prominent outline with an optional
 * label, so the annotated image is useful both during the assessment and in the
 * final report. Coordinates are in the image's own pixel space.
 */
public class Annotation {
    public String id;
    public int x;
    public int y;
    public int width;
    public int height;
    public String label = "";
    public String description = "";
    /** Id of a related request, if the callout ties to specific traffic. */
    public String relatedRequestId;
    public String timestamp;
}
