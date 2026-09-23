package com.assessmentnotebook.model;

/**
 * A captured HTTP response paired with a {@link RequestRecord}. Body bytes are
 * written under {@code source/} and referenced by {@link #rawFile}; parsed
 * fields support display, reflection review and cross-referencing.
 */
public class ResponseRecord {
    public String id;
    public int statusCode;
    public String reasonPhrase = "";
    public String contentType = "";
    public java.util.List<String> headers = new java.util.ArrayList<>();
    /** May be omitted or truncated for very large bodies; see rawFile. */
    public String body = "";
    /** Relative path to the saved raw response, if written. */
    public String rawFile;
    public String createdAt;
}
