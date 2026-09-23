package com.assessmentnotebook.burp;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.assessmentnotebook.analyze.HtmlAnalyzer;
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
        return reg;
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
