package com.assessmentnotebook.analyze;

import java.util.ArrayList;
import java.util.List;

/**
 * The structured result of analyzing a response body: everything the extension
 * detected and can offer to the tester for review before anything is committed
 * to the project. This is a proposal, not a decision.
 */
public class DiscoveredPage {
    public String title = "";
    public String contentType = "";

    public final List<DiscoveredForm> forms = new ArrayList<>();
    public final List<DiscoveredLink> links = new ArrayList<>();
    public final List<DiscoveredResource> resources = new ArrayList<>();

    /** A link or navigational edge found in the response. */
    public static class DiscoveredLink {
        public String url = "";
        public String text = "";
        public String elementType = "";   // anchor, form-action, redirect, ...
        public String method = "";          // GET/POST where known
    }

    /** A form and its inputs. */
    public static class DiscoveredForm {
        public String action = "";
        public String method = "GET";
        public String encType = "";
        public String identifier = "";
        public final List<DiscoveredInput> inputs = new ArrayList<>();
    }

    /** A single form control. */
    public static class DiscoveredInput {
        public String name = "";
        public String type = "";           // text, password, hidden, file, ...
        public String value = "";
        public boolean required = false;
    }

    /** A script/stylesheet/image/font/endpoint the page references. */
    public static class DiscoveredResource {
        public String url = "";
        public String type = "";           // SCRIPT, STYLESHEET, IMAGE, FONT, ...
        /**
         * Optional captured body for this resource. When present (the tester
         * ticked "Capture resource bodies"), registration saves it as the
         * resource's source copy so the notebook holds the actual asset, not
         * just its URL. Left empty when only the identity is being recorded.
         */
        public String body = "";
        /** Where {@link #body} came from, for the saved-copy provenance note. */
        public String bodySource = "";     // e.g. "proxy history", "live fetch"
    }
}
