package com.assessmentnotebook.burp;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.params.ParsedHttpParameter;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.assessmentnotebook.analyze.DiscoverySourceInference;
import com.assessmentnotebook.analyze.DiscoveredPage;
import com.assessmentnotebook.analyze.HtmlAnalyzer;
import com.assessmentnotebook.analyze.JsonRequestForm;
import com.assessmentnotebook.core.PageRegistration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link PageRegistration} proposal from a Burp {@link
 * HttpRequestResponse}. It parses the fields, preserves the raw bytes as
 * evidence, and (for HTML responses) runs the {@link HtmlAnalyzer} so the
 * tester sees the detected forms, links and resources before confirming.
 */
public final class RequestExtractor {
    private final HtmlAnalyzer analyzer = new HtmlAnalyzer();

    public PageRegistration toRegistration(HttpRequestResponse rr) {
        return toRegistration(rr, null);
    }

    /** @param toolName originating Burp tool (e.g. "Proxy"), or null if unknown. */
    public PageRegistration toRegistration(HttpRequestResponse rr, String toolName) {
        PageRegistration reg = new PageRegistration();
        HttpRequest request = rr.request();
        reg.url = request.url();
        reg.method = request.method();
        reg.requestHeaders = headerLines(request.headers());
        reg.requestBody = request.bodyToString();
        reg.rawRequest = request.toString().getBytes(StandardCharsets.UTF_8);

        HttpService svc = rr.httpService();
        if (svc != null) {
            reg.host = svc.host();
            reg.port = svc.port();
            reg.secure = svc.secure();
        }

        if (rr.hasResponse()) {
            HttpResponse response = rr.response();
            reg.statusCode = response.statusCode();
            reg.reasonPhrase = response.reasonPhrase();
            reg.contentType = contentType(response);
            reg.responseHeaders = headerLines(response.headers());
            reg.rawResponse = response.toString().getBytes(StandardCharsets.UTF_8);
            String body = response.bodyToString();
            reg.pageSource = body;
            if (reg.contentType.toLowerCase().contains("html")) {
                reg.discovered = analyzer.analyze(body, reg.url);
            }
        }
        if (reg.discovered.title.isBlank()) {
            reg.discovered.title = "";
        }

        // Document the request's own parameters as a form, so JSON/XHR endpoints
        // (which have no HTML <form>) still expose their fields — this is what
        // lets parameter probes attach to e.g. a JSON login field and render.
        DiscoveredPage.DiscoveredForm reqForm = requestParamForm(request, reg.url);
        if (reqForm != null) reg.discovered.forms.add(reqForm);

        DiscoverySourceInference.Result guess =
                DiscoverySourceInference.infer(reg.method, reg.requestHeaders, toolName);
        reg.discoverySourceKind = guess.kind;
        reg.discoverySource = guess.detail;
        return reg;
    }

    /**
     * Build a documentable form from the request's own parameters: JSON body
     * fields (flattened) when the request is JSON, otherwise its query and body
     * parameters. Returns null when the request carries no parameters.
     */
    private DiscoveredPage.DiscoveredForm requestParamForm(HttpRequest request, String url) {
        String ct = requestContentType(request);
        if (ct != null && ct.toLowerCase().contains("json")) {
            return JsonRequestForm.build(url, request.method(), request.bodyToString());
        }
        DiscoveredPage.DiscoveredForm df = new DiscoveredPage.DiscoveredForm();
        df.action = url;
        df.method = request.method();
        df.encType = ct == null ? "" : ct;
        df.identifier = "request parameters";
        try {
            for (ParsedHttpParameter p : request.parameters()) {
                String type;
                switch (p.type()) {
                    case URL:  type = "query"; break;
                    case BODY: type = "body"; break;
                    default:   continue; // cookies etc. are not request parameters here
                }
                DiscoveredPage.DiscoveredInput in = new DiscoveredPage.DiscoveredInput();
                in.name = p.name();
                in.type = type;
                in.value = p.value();
                df.inputs.add(in);
            }
        } catch (RuntimeException ignored) { /* no parsable params */ }
        return df.inputs.isEmpty() ? null : df;
    }

    private static String requestContentType(HttpRequest request) {
        try {
            for (HttpHeader h : request.headers()) {
                if ("content-type".equalsIgnoreCase(h.name())) return h.value();
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    /**
     * Build a {@link com.assessmentnotebook.model.PageVariant} from a selected
     * request/response, capturing its request conditions (query/body/JSON
     * parameters, cookies, auth context) so it can be compared against other
     * variants of the same page.
     */
    public com.assessmentnotebook.model.PageVariant toVariant(HttpRequestResponse rr, String label) {
        com.assessmentnotebook.model.PageVariant v = new com.assessmentnotebook.model.PageVariant();
        HttpRequest request = rr.request();
        v.url = request.url();
        v.method = request.method();
        v.label = label == null ? "" : label;

        try {
            for (burp.api.montoya.http.message.params.ParsedHttpParameter p : request.parameters()) {
                switch (p.type()) {
                    case URL:    v.queryParams.put(p.name(), p.value()); break;
                    case BODY:   v.bodyParams.put(p.name(), p.value()); break;
                    case COOKIE: v.cookies.put(p.name(), p.value()); break;
                    default: break;
                }
            }
        } catch (RuntimeException ignored) { /* best effort */ }

        String ctReq = request.headerValue("Content-Type");
        if (ctReq != null && ctReq.toLowerCase().contains("json")) {
            v.jsonParams.putAll(
                    com.assessmentnotebook.analyze.JsonParameters.flatten(request.bodyToString()));
        }

        boolean hasAuth = request.headerValue("Authorization") != null;
        v.authContext = hasAuth ? "Authorization header present" : "anonymous";
        v.relevantHeaders = new ArrayList<>();
        if (ctReq != null) v.relevantHeaders.add("Content-Type: " + ctReq);
        if (hasAuth) v.relevantHeaders.add("Authorization: present");

        if (rr.hasResponse()) {
            HttpResponse response = rr.response();
            v.statusCode = response.statusCode();
            v.contentType = contentType(response);
        }
        return v;
    }

    private static String contentType(HttpResponse response) {
        String header = response.headerValue("Content-Type");
        if (header != null && !header.isBlank()) return header;
        return response.statedMimeType() != null ? response.statedMimeType().toString() : "";
    }

    private static List<String> headerLines(List<HttpHeader> headers) {
        List<String> out = new ArrayList<>();
        if (headers != null) {
            for (HttpHeader h : headers) out.add(h.name() + ": " + h.value());
        }
        return out;
    }
}
