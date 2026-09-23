package com.assessmentnotebook.model;

/**
 * A hyperlink or navigational edge discovered on a page. Links both document
 * what a page points at and, when the destination is itself a registered page,
 * establish a "links to" {@link Relationship} between the two page documents.
 */
public class Link {
    public String id;
    /** Id of the page the link was found on. */
    public String sourcePageId;
    public String destinationUrl = "";
    /** GET/POST where known; blank for a plain anchor. */
    public String method = "";
    public String visibleText = "";
    /** anchor, form-action, redirect, script, header, ... */
    public String elementType = "";
    /** How the link was discovered (crawl, response header, JS, manual). */
    public String discoveryMethod = "";
    /** Id of the destination page, if it has been registered. May be null. */
    public String destinationPageId;
    public String notes = "";
    public String createdAt;
    public String updatedAt;
}
