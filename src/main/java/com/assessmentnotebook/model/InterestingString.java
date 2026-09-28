package com.assessmentnotebook.model;

/**
 * A string the tester marked as worth keeping for later wordlists (spec §8):
 * an admin term, an internal hostname, an API path, an unusual identifier. Each
 * carries where it came from and its context so a raw wordlist can later be
 * turned back into leads.
 */
public class InterestingString {
    /** Wordlist buckets these strings can be exported into (spec §8). */
    public enum Category {
        GENERAL("General", "general"),
        DIRECTORIES("Directories", "directories"),
        PARAMETERS("Parameters", "parameters"),
        USERNAMES("Usernames", "usernames"),
        TECHNOLOGY_SPECIFIC("Technology-specific", "technology"),
        APPLICATION_TERMINOLOGY("Application terminology", "terminology"),
        CUSTOM("Custom", "custom");

        public final String label;
        /** Slug used for the exported wordlist file name. */
        public final String slug;
        Category(String label, String slug) { this.label = label; this.slug = slug; }
    }

    public String id;
    public String value = "";
    public Category category = Category.GENERAL;
    /** Id of the page it was seen on, if any. */
    public String sourcePageId;
    /** The element/where it appeared, e.g. "link text", "hidden input name". */
    public String sourceElement = "";
    /** A short surrounding excerpt for context. */
    public String context = "";
    public String notes = "";
    public String timestamp;
}
