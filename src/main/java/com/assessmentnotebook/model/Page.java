package com.assessmentnotebook.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A discovered page or endpoint. The central node of the application model:
 * forms, links, resources, screenshots and interactions all hang off a page,
 * and discovery/link relationships between pages build the application graph.
 */
public class Page {
    /**
     * What sort of thing answered at this URL. An API endpoint is modeled as a
     * page (it has request parameters, variants, evidence and notes like any
     * other) but is labeled and grouped apart from the HTML pages a user sees.
     */
    public enum Kind {
        PAGE("Page"),
        API("API endpoint");

        public final String label;
        Kind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public String id;
    public Kind kind = Kind.PAGE;
    public String url = "";
    public String title = "";
    public String method = "GET";
    public int statusCode;
    public String contentType = "";
    /** How this page was found (controlled vocabulary; spec §3). */
    public DiscoverySource discoverySourceKind = DiscoverySource.MANUAL;
    /** Free-text specifics for the discovery, e.g. the referring URL. */
    public String discoverySource = "";
    public String firstSeen;
    public String lastSeen;

    /** Child entity ids (the authoritative membership lives here). */
    public List<String> formIds = new ArrayList<>();
    public List<String> linkIds = new ArrayList<>();
    public List<String> resourceIds = new ArrayList<>();
    public List<String> screenshotIds = new ArrayList<>();
    public List<String> interactionIds = new ArrayList<>();
    public List<String> variantIds = new ArrayList<>();
    /** Saved source files for this page, relative to the project root. */
    public List<String> sourceFiles = new ArrayList<>();

    public String notes = "";

    /**
     * The path with id-like segments collapsed ({@code /api/users/{id}}), when
     * auto-capture grouped several concrete URLs under this record; else blank.
     */
    public String pathTemplate = "";
    /** Field paths seen in this endpoint's JSON responses ({@code items[].id}). */
    public List<String> responseFields = new ArrayList<>();
    /**
     * Capture fingerprints already documented for this page (see
     * {@code CaptureFingerprint}); the first is the baseline. A new sighting
     * whose fingerprint is listed here adds nothing and is skipped.
     */
    public List<String> fingerprints = new ArrayList<>();
    /**
     * The first capture's request conditions, held here until a differing
     * capture arrives; it is then promoted to a real variant so the two can be
     * compared. Null once promoted, and for pages that predate auto-capture.
     */
    public PageVariant baseline;
}
