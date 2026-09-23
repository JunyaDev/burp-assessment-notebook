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
    public String url = "";
    /** Optional saved copy of the resource, relative to the project root. */
    public String sourceFile;
    /** Ids of pages that load or reference this resource. */
    public List<String> pageIds = new ArrayList<>();
    public String notes = "";
    public String createdAt;
    public String updatedAt;
}
