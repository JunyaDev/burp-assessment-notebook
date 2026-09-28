package com.assessmentnotebook.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A non-page resource associated with one or more pages: a script, stylesheet,
 * image, font, or an API/XHR endpoint the page talks to. Kept separate from
 * pages so a shared asset (a bundle loaded everywhere, say) is documented once
 * and linked from every page that loads it.
 */
public class Resource {
    public enum Type {
        SCRIPT("JavaScript"),
        STYLESHEET("CSS"),
        IMAGE("Image"),
        FONT("Font"),
        API("API endpoint"),
        XHR("XHR/fetch endpoint"),
        OTHER("Other");

        public final String label;
        Type(String label) { this.label = label; }
    }

    public String id;
    public Type type = Type.OTHER;
    /** The canonical URL that identifies this resource (see ResourceUrls). */
    public String url = "";
    /** Every raw URL form under which this resource was actually seen. */
    public List<String> observedUrls = new ArrayList<>();
    /** Optional saved copy of the resource, relative to the project root. */
    public String sourceFile;
    /** Ids of pages that load or reference this resource. */
    public List<String> pageIds = new ArrayList<>();
    public String notes = "";
    public String createdAt;
    public String updatedAt;

    /**
     * Type precedence for merging: a later, more specific classification should
     * win over a generic {@link Type#OTHER}. Higher rank = more specific.
     */
    public static int specificity(Type t) {
        return t == Type.OTHER ? 0 : 1;
    }
}
