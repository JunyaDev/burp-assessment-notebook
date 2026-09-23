package com.assessmentnotebook.model;

/**
 * A technology observed on the target, recorded as a structured entry rather
 * than a line in a free-text blob so it can be grouped by {@link Category} and
 * linked to the evidence that revealed it.
 */
public class Technology {
    /** Broad grouping used to organize the technologies table on the overview. */
    public enum Category {
        WEB_SERVER("Web server"),
        OPERATING_SYSTEM("Operating system"),
        LANGUAGE("Programming language"),
        FRAMEWORK("Framework"),
        JS_FRAMEWORK("JavaScript framework"),
        DATABASE("Database"),
        CMS("CMS"),
        AUTHENTICATION("Authentication"),
        THIRD_PARTY("Third-party service"),
        OTHER("Other");

        public final String label;
        Category(String label) { this.label = label; }
    }

    public String id;
    public Category category = Category.OTHER;
    public String name = "";
    public String version = "";
    /** How this was determined, e.g. "Server header", "cookie name", "favicon". */
    public String evidence = "";
    public String notes = "";
    public String createdAt;
    public String updatedAt;
}
