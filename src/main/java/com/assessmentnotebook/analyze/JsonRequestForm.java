package com.assessmentnotebook.analyze;

import java.util.Map;

/**
 * Synthesizes a documentable "form" from a JSON request body so that XHR/JSON
 * endpoints (which have no HTML &lt;form&gt;) still expose their fields as
 * parameters. This is what lets parameter probes attach to a JSON field like the
 * login {@code email}: registration creates a Parameter per JSON key, and a
 * later probe run resolves to it by name and renders on the form document.
 */
public final class JsonRequestForm {
    private JsonRequestForm() {}

    /**
     * @return a DiscoveredForm whose inputs are the flattened JSON keys, or null
     *         when the body has no JSON parameters.
     */
    public static DiscoveredPage.DiscoveredForm build(String url, String method, String body) {
        Map<String, String> flat = JsonParameters.flatten(body);
        if (flat.isEmpty()) return null;
        DiscoveredPage.DiscoveredForm df = new DiscoveredPage.DiscoveredForm();
        df.action = url == null ? "" : url;
        df.method = method == null ? "POST" : method;
        df.encType = "application/json";
        df.identifier = "request parameters (json)";
        for (Map.Entry<String, String> e : flat.entrySet()) {
            DiscoveredPage.DiscoveredInput in = new DiscoveredPage.DiscoveredInput();
            in.name = e.getKey();
            in.type = "json";
            in.value = e.getValue();
            df.inputs.add(in);
        }
        return df;
    }
}
