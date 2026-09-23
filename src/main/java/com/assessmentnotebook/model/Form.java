package com.assessmentnotebook.model;

import java.util.ArrayList;
import java.util.List;

/**
 * An HTML form discovered on a page, documented in detail because forms are
 * where most interesting behavior (auth, search, upload, reflection) lives.
 *
 * <p>Parameters are held by id and resolved through the {@link Project}, so the
 * same parameter entity can be referenced from a generated request as well.
 */
public class Form {
    public String id;
    /** Id of the page this form was found on. */
    public String pageId;
    public String action = "";
    public String method = "GET";
    /** enctype, e.g. application/x-www-form-urlencoded or multipart/form-data. */
    public String encType = "";
    /** The form's own name/id attribute, if any. */
    public String formIdentifier = "";
    /** Human description of where on the page the form sits. */
    public String locationOnPage = "";
    /** Ids of {@link Parameter} entities belonging to this form. */
    public List<String> parameterIds = new ArrayList<>();
    public String notes = "";
    public String createdAt;
    public String updatedAt;
}
