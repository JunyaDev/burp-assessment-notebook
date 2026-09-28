package com.assessmentnotebook.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A technology observed on the target. Recorded with a confidence/evidence
 * model (spec §5, §6) so an automatic detection is never treated as
 * authoritative: every entry carries the evidence that supports it, a
 * confidence level, and a change history, and every field is editable by the
 * tester. A stronger, later observation (e.g. a precise version) updates the
 * existing entry rather than creating a duplicate.
 */
public class Technology {
    /** Broad grouping used to organize the technologies table on the overview. */
    public enum Category {
        WEB_SERVER("Web server"),
        OPERATING_SYSTEM("Operating system"),
        LANGUAGE("Programming language"),
        FRAMEWORK("Framework"),
        JS_FRAMEWORK("JavaScript framework"),
        JS_LIBRARY("JavaScript library"),
        DATABASE("Database"),
        CMS("CMS"),
        ANALYTICS("Analytics"),
        CDN("CDN"),
        UI_FRAMEWORK("UI framework"),
        AUTHENTICATION("Authentication"),
        THIRD_PARTY("Third-party service"),
        OTHER("Other");

        public final String label;
        Category(String label) { this.label = label; }
    }

    /** How sure we are, ordered from weakest to strongest. */
    public enum Confidence {
        LOW("Low"), MEDIUM("Medium"), HIGH("High"), CONFIRMED("Confirmed");
        public final String label;
        Confidence(String label) { this.label = label; }
    }

    /** One piece of evidence supporting the detection. */
    public static class Evidence {
        /** Where it came from: "HTTP header", "cookie", "script URL", "meta", ... */
        public String source = "";
        /** The concrete snippet, e.g. {@code Server: nginx/1.25.3}. */
        public String detail = "";
        public String observedAt = "";

        public Evidence() {}
        public Evidence(String source, String detail, String observedAt) {
            this.source = source;
            this.detail = detail;
            this.observedAt = observedAt;
        }
    }

    public String id;
    public Category category = Category.OTHER;
    public String name = "";
    public String version = "";
    public Confidence confidence = Confidence.MEDIUM;
    /** Structured evidence entries (preferred over the legacy string). */
    public List<Evidence> evidences = new ArrayList<>();
    /** Legacy single-line evidence; kept so old projects still render. */
    public String evidence = "";
    /** Whether a human confirmed/edited this (blocks auto-overwrite of fields). */
    public boolean userEdited = false;
    public String notes = "";
    /** Human-readable change log ("version: '' -> 18.3.1"). */
    public List<String> history = new ArrayList<>();
    public String firstObserved = "";
    public String lastUpdated = "";
    public String createdAt;
    public String updatedAt;

    /** All evidence lines for display, merging legacy + structured. */
    public List<String> evidenceLines() {
        List<String> out = new ArrayList<>();
        if (evidence != null && !evidence.isBlank()) out.add(evidence);
        for (Evidence e : evidences) {
            String line = (e.source == null || e.source.isBlank() ? "" : e.source + ": ")
                    + (e.detail == null ? "" : e.detail);
            if (!line.isBlank()) out.add(line);
        }
        return out;
    }
}
