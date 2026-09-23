package com.assessmentnotebook.model;

/**
 * Top-level facts about the engagement target, shown on {@code index.html}.
 *
 * <p>Plain mutable fields keep JSON (de)serialization trivial and let the Burp
 * UI edit them in place. All fields are optional; blanks render as em-dashes.
 */
public class TargetInfo {
    public String domain = "";
    public String mainUrl = "";
    public String assessmentName = "";
    /** Free-form date string (the tester decides the format). */
    public String assessmentDate = "";
    public String notes = "";
}
