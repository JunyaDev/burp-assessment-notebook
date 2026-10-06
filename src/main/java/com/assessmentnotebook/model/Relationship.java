package com.assessmentnotebook.model;

import java.util.Objects;

/**
 * A directed, typed edge between two entities. Relationships are first-class
 * data (not merely implied by text) so the application model is a graph: a
 * page can be discovered via several paths, a resource can belong to many
 * pages, and a finding can reach its evidence by following edges.
 */
public class Relationship {
    /** Common edge labels. Stored as a string so new kinds need no schema change. */
    public static final String DISCOVERED = "discovered";
    public static final String LINKS_TO = "links-to";
    public static final String REDIRECTS_TO = "redirects-to";
    public static final String CONTAINS_FORM = "contains-form";
    public static final String CONTAINS_LINK = "contains-link";
    public static final String LOADS_RESOURCE = "loads-resource";
    /** A page whose scripts call an API endpoint (from the request's Referer). */
    public static final String CALLS = "calls";
    public static final String HAS_PARAMETER = "has-parameter";
    public static final String GENERATES_REQUEST = "generates-request";
    public static final String PRODUCES_RESPONSE = "produces-response";
    public static final String AFFECTS = "affects";
    public static final String EVIDENCED_BY = "evidenced-by";
    public static final String RELATES_TO = "relates-to";

    public EntityType fromType;
    public String fromId;
    public EntityType toType;
    public String toId;
    /** Edge label, typically one of the constants above. */
    public String kind = RELATES_TO;
    /** Optional human note on the edge (e.g. "via 302 redirect"). */
    public String detail = "";
    public String createdAt;

    public Relationship() {}

    public Relationship(EntityType fromType, String fromId, String kind,
                        EntityType toType, String toId) {
        this.fromType = fromType;
        this.fromId = fromId;
        this.kind = kind;
        this.toType = toType;
        this.toId = toId;
    }

    /** Edges are identified by their endpoints and label for de-duplication. */
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Relationship)) return false;
        Relationship r = (Relationship) o;
        return fromType == r.fromType && toType == r.toType
                && Objects.equals(fromId, r.fromId)
                && Objects.equals(toId, r.toId)
                && Objects.equals(kind, r.kind);
    }

    @Override public int hashCode() {
        return Objects.hash(fromType, fromId, toType, toId, kind);
    }
}
