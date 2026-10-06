package com.assessmentnotebook.analyze;

import com.assessmentnotebook.model.Resource;

import java.util.List;
import java.util.Locale;

/**
 * Decides what an observed exchange <i>is</i>, so the tester does not have to:
 * an HTML page, an API endpoint (JSON/XML/XHR — the data calls a single-page or
 * Flutter front end makes), or a static resource. Each exchange gets exactly
 * one kind, which is what stops a JSON call from being registered three times
 * "to be safe".
 *
 * <p>Signals, strongest first: the response content type, the URL extension,
 * and the browser's {@code Sec-Fetch-Dest} / {@code X-Requested-With} request
 * headers. Nothing here reads a body.
 */
public final class TrafficClassifier {
    private TrafficClassifier() {}

    public enum Kind { PAGE, API, RESOURCE, SKIP }

    public static final class Result {
        public final Kind kind;
        /** Set when {@link #kind} is RESOURCE. */
        public final Resource.Type resourceType;

        Result(Kind kind, Resource.Type resourceType) {
            this.kind = kind;
            this.resourceType = resourceType;
        }
    }

    private static final Result PAGE = new Result(Kind.PAGE, null);
    private static final Result API = new Result(Kind.API, null);
    private static final Result SKIP = new Result(Kind.SKIP, null);

    /**
     * @param requestHeaders      request headers as "Name: value" lines (may be null)
     * @param responseContentType response Content-Type value (may be null/blank)
     */
    public static Result classify(String method, String url, List<String> requestHeaders,
            int status, String responseContentType) {
        String ct = responseContentType == null ? "" : responseContentType.toLowerCase(Locale.ROOT);
        String dest = header(requestHeaders, "sec-fetch-dest").toLowerCase(Locale.ROOT);

        // A CORS preflight is the browser asking permission, not the application talking.
        if ("OPTIONS".equalsIgnoreCase(method)
                && !header(requestHeaders, "access-control-request-method").isEmpty()) {
            return SKIP;
        }
        Resource.Type asset = assetType(ct, extension(url), dest);
        if (asset != null) return new Result(Kind.RESOURCE, asset);
        // Not Modified carries no body: there is nothing to document or compare.
        if (status == 304) return SKIP;

        if (ct.contains("html")) return PAGE;
        if (isDataType(ct)) return API;

        boolean xhr = dest.equals("empty")
                || header(requestHeaders, "x-requested-with").toLowerCase(Locale.ROOT)
                        .contains("xmlhttprequest");
        boolean jsonRequest = header(requestHeaders, "content-type").toLowerCase(Locale.ROOT)
                .contains("json");
        if (xhr || jsonRequest) return API;

        // A redirect or bodiless answer to a navigation is still part of the page flow.
        if (status >= 300 && status < 400) return PAGE;
        if (dest.equals("document") || dest.equals("iframe") || dest.equals("frame")) return PAGE;
        boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        if (!read) return API;
        return new Result(Kind.RESOURCE, Resource.Type.OTHER);
    }

    /** Content types that carry data for a script rather than a document for a person. */
    private static boolean isDataType(String ct) {
        return ct.contains("json") || ct.contains("xml") || ct.contains("graphql")
                || ct.contains("protobuf") || ct.contains("grpc") || ct.contains("msgpack")
                || ct.contains("cbor") || ct.contains("event-stream")
                || ct.contains("x-www-form-urlencoded");
    }

    private static Resource.Type assetType(String ct, String ext, String dest) {
        if (ct.contains("javascript") || ct.contains("ecmascript") || dest.equals("script")
                || dest.endsWith("worker") || ext.equals("js") || ext.equals("mjs")
                || ext.equals("cjs")) {
            return Resource.Type.SCRIPT;
        }
        if (ct.contains("text/css") || dest.equals("style") || ext.equals("css")) {
            return Resource.Type.STYLESHEET;
        }
        // Checked before the data types: image/svg+xml is an image, not an API.
        if (ct.startsWith("image/") || dest.equals("image")) return Resource.Type.IMAGE;
        switch (ext) {
            case "png": case "jpg": case "jpeg": case "gif": case "svg":
            case "webp": case "ico": case "bmp": case "avif":
                return Resource.Type.IMAGE;
            case "woff": case "woff2": case "ttf": case "otf": case "eot":
                return Resource.Type.FONT;
            case "map": case "wasm":
                return Resource.Type.OTHER;
            default:
                break;
        }
        if (ct.startsWith("font/") || ct.contains("font-") || dest.equals("font")) {
            return Resource.Type.FONT;
        }
        if (ct.startsWith("audio/") || ct.startsWith("video/") || ct.contains("wasm")
                || dest.equals("audio") || dest.equals("video") || dest.equals("track")
                || dest.equals("manifest")) {
            return Resource.Type.OTHER;
        }
        return null;
    }

    private static String extension(String url) {
        String path = UrlTemplates.path(url).toLowerCase(Locale.ROOT);
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1) : "";
    }

    private static String header(List<String> headers, String name) {
        if (headers == null) return "";
        String want = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : headers) {
            if (line != null && line.toLowerCase(Locale.ROOT).startsWith(want)) {
                return line.substring(want.length()).trim();
            }
        }
        return "";
    }
}
