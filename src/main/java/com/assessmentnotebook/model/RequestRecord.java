package com.assessmentnotebook.model;

/**
 * A captured HTTP request, stored so findings and interactions can cite the
 * exact traffic that evidences them. The raw request bytes are written to a
 * file under {@code source/} and referenced by {@link #rawFile}; the parsed
 * fields here are for display and cross-referencing.
 */
public class RequestRecord {
    public String id;
    public String method = "";
    public String url = "";
    public String host = "";
    public int port;
    public boolean secure;
    /** Headers as raw "Name: value" lines, in order. */
    public java.util.List<String> headers = new java.util.ArrayList<>();
    public String body = "";
    /** Relative path to the saved raw request, if written. */
    public String rawFile;
    /** Id of the response produced by this request, if captured. */
    public String responseId;
    public String createdAt;
}
