package com.assessmentnotebook.core;

import com.assessmentnotebook.analyze.DiscoveredPage;

import java.util.List;

/**
 * The tester-confirmed proposal to register (or update) a page. The Burp UI
 * fills this from a selected request/response and the analyzer's findings, lets
 * the tester prune it, and hands it to {@link NotebookController#registerPage}.
 *
 * <p>Only what is present here is committed: nothing is registered behind the
 * tester's back.
 */
public class PageRegistration {
    public String url = "";
    public String method = "GET";
    /** Page or API endpoint; suggested by the traffic classifier, editable. */
    public com.assessmentnotebook.model.Page.Kind kind =
            com.assessmentnotebook.model.Page.Kind.PAGE;
    public int statusCode;
    public String contentType = "";
    /** How the page was found (spec §3); inferred at extraction, editable. */
    public com.assessmentnotebook.model.DiscoverySource discoverySourceKind =
            com.assessmentnotebook.model.DiscoverySource.MANUAL;
    /** Free-text specifics for the discovery, e.g. the referring URL. */
    public String discoverySource = "";
    /** Id of the page this one was discovered from, if known. */
    public String parentPageId;

    /** Confirmed forms/links/resources to record (a pruned analyzer result). */
    public DiscoveredPage discovered = new DiscoveredPage();

    /** Raw request bytes to preserve as evidence, if any. */
    public byte[] rawRequest;
    /** Raw response bytes to preserve as evidence, if any. */
    public byte[] rawResponse;

    // Parsed request/response fields (optional, for the RequestRecord).
    public String host = "";
    public int port;
    public boolean secure;
    public List<String> requestHeaders;
    public String requestBody = "";
    public String reasonPhrase = "";
    public List<String> responseHeaders;

    /** Response body to preserve as the page's captured, highlightable source. */
    public String pageSource = "";
}
