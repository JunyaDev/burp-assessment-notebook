package com.assessmentnotebook.analyze;

import com.assessmentnotebook.model.DiscoverySource;

import java.util.List;
import java.util.Locale;

/**
 * Infers how a request was reached from signals available at capture time
 * (spec §3): the originating Burp tool, the {@code Referer}/{@code Origin}
 * headers, whether it is an XHR/fetch, and the method. Redirect discovery is
 * inferred separately in the controller, where prior responses are known.
 *
 * <p>The result is only a suggestion; the tester can override it in the
 * Register dialog.
 */
public final class DiscoverySourceInference {
    private DiscoverySourceInference() {}

    /** A kind plus a human detail (e.g. the referring URL), both editable. */
    public static final class Result {
        public final DiscoverySource kind;
        public final String detail;
        public Result(DiscoverySource kind, String detail) {
            this.kind = kind;
            this.detail = detail == null ? "" : detail;
        }
    }

    /**
     * @param method    HTTP method
     * @param headers   request headers as "Name: value" lines (may be null)
     * @param toolName  originating Burp tool name, e.g. "Proxy"/"Repeater" (may be null)
     */
    public static Result infer(String method, List<String> headers, String toolName) {
        String referer = header(headers, "referer");
        boolean xhr = isXhr(headers);

        if (xhr) {
            return new Result(DiscoverySource.XHR_FETCH,
                    referer.isEmpty() ? "" : "from " + referer);
        }
        if (!referer.isEmpty()) {
            return new Result(DiscoverySource.LINK_FROM_PAGE, "from " + referer);
        }
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)) {
            return new Result(DiscoverySource.FORM_SUBMISSION, "");
        }
        String tool = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (tool.contains("repeater")) {
            return new Result(DiscoverySource.BURP_REPEATER, "");
        }
        if (tool.contains("proxy") || tool.contains("history") || tool.contains("target")) {
            return new Result(DiscoverySource.BURP_HISTORY, "");
        }
        return new Result(DiscoverySource.DIRECT_NAVIGATION, "");
    }

    private static boolean isXhr(List<String> headers) {
        String requestedWith = header(headers, "x-requested-with");
        if (requestedWith.toLowerCase(Locale.ROOT).contains("xmlhttprequest")) return true;
        String dest = header(headers, "sec-fetch-dest").toLowerCase(Locale.ROOT);
        String mode = header(headers, "sec-fetch-mode").toLowerCase(Locale.ROOT);
        if (dest.equals("empty") && (mode.equals("cors") || mode.equals("same-origin"))) return true;
        String accept = header(headers, "accept").toLowerCase(Locale.ROOT);
        return accept.contains("application/json") && !accept.contains("text/html");
    }

    private static String header(List<String> headers, String name) {
        if (headers == null) return "";
        String want = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : headers) {
            if (line != null && line.toLowerCase(Locale.ROOT).startsWith(want)) {
                int colon = line.indexOf(':');
                return colon >= 0 ? line.substring(colon + 1).trim() : "";
            }
        }
        return "";
    }
}
