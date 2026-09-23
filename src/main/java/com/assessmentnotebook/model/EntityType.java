package com.assessmentnotebook.model;

/**
 * The kinds of node that can participate in a {@link Relationship}.
 *
 * <p>Every documented entity in a project is identified by a stable id string
 * (see {@link Project#nextId(EntityType)}) and one of these types. Relationships
 * are expressed as typed edges between (type,id) pairs so the same resource can
 * be connected to many pages, forms and findings at once.
 */
public enum EntityType {
    PROJECT("project"),
    TECHNOLOGY("tech"),
    PAGE("page"),
    RESOURCE("res"),
    LINK("link"),
    FORM("form"),
    PARAMETER("param"),
    INTERACTION("act"),
    REQUEST("req"),
    RESPONSE("resp"),
    SCREENSHOT("shot"),
    NOTE("note"),
    VULNERABILITY("vuln");

    /** Short slug used as the prefix of generated ids, e.g. {@code page-0007}. */
    public final String slug;

    EntityType(String slug) {
        this.slug = slug;
    }
}
