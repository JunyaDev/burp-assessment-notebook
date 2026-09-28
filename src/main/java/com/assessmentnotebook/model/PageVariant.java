package com.assessmentnotebook.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One captured rendering of a page under specific request conditions (spec §12).
 * The same URL can produce different content depending on query/body/JSON
 * parameters, cookies, headers and auth state; a variant records those
 * conditions together with the response so variants can be compared (spec §13).
 *
 * <p>Derived descriptors ({@link #title}, {@link #bodyLength},
 * {@link #structureSignature}, {@link #reflectedInputNames}) are computed once at
 * registration so comparison needs only the model, not the saved files.
 */
public class PageVariant {
    public String id;
    public String pageId;
    public String label = "";

    // ---- request conditions ---------------------------------------------
    public String method = "GET";
    public String url = "";
    public Map<String, String> queryParams = new LinkedHashMap<>();
    public Map<String, String> bodyParams = new LinkedHashMap<>();
    public Map<String, String> jsonParams = new LinkedHashMap<>();
    public List<String> relevantHeaders = new ArrayList<>();
    public Map<String, String> cookies = new LinkedHashMap<>();
    public String authContext = "";

    // ---- response -------------------------------------------------------
    public int statusCode;
    public String contentType = "";
    public int bodyLength;
    public String title = "";
    /** Structural fingerprint for shape comparison (see ResponseShape). */
    public String structureSignature = "";
    /** Names of inputs whose submitted value was reflected in the response. */
    public List<String> reflectedInputNames = new ArrayList<>();
    /** Saved response body, relative to the project root. */
    public String sourceFile;
    public String screenshotId;
    public String timestamp;

    /** All request inputs merged into one map (query, body, JSON). */
    public Map<String, String> allInputs() {
        Map<String, String> all = new LinkedHashMap<>();
        all.putAll(queryParams);
        all.putAll(bodyParams);
        all.putAll(jsonParams);
        return all;
    }
}
