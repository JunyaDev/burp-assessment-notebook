package com.assessmentnotebook.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A discovered page or endpoint. The central node of the application model:
 * forms, links, resources, screenshots and interactions all hang off a page,
 * and discovery/link relationships between pages build the application graph.
 */
public class Page {
    public String id;
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
}
