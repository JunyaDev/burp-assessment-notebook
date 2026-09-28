package com.assessmentnotebook.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.params.HttpParameter;
import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.assessmentnotebook.analyze.JsonParameters;
import com.assessmentnotebook.analyze.ProbeGenerator;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.model.ParameterTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sends the configured non-destructive probes for one parameter and records the
 * results (spec §14–§18). It only ever runs when the tester has confirmed
 * authorization in the dialog, and it sends the probe values from
 * {@link ProbeGenerator} — no destructive payloads. The whole run is persisted
 * once at the end through the controller, so a run of a dozen probes costs one
 * save and one regeneration of the affected documents, not a dozen.
 */
public final class ParameterTester {
    private final MontoyaApi api;
    private final NotebookController controller;

    public ParameterTester(MontoyaApi api, NotebookController controller) {
        this.api = api;
        this.controller = controller;
    }

    /**
     * @param baseline    the request/response the tester selected
     * @param paramName   parameter name (or JSON dotted path)
     * @param source      QUERY, BODY or JSON
     * @param probes      the probe set to run
     * @param pageId      page to link results to (may be null)
     * @param parameterId parameter entity to link results to (may be null; the
     *                    controller resolves it from the page's forms by name)
     * @param progress    receives a short status line per step (may be null)
     * @return the recorded tests
     */
    public List<ParameterTest> run(HttpRequestResponse baseline, String paramName,
            ParameterTest.Source source, List<ProbeGenerator.Probe> probes,
            String pageId, String parameterId, Consumer<String> progress) throws IOException {
        List<ParameterTest> out = new ArrayList<>();
        HttpRequest original = baseline.request();
        String originalBody = original.bodyToString();

        // Baseline: prefer the response already captured with the request so a
        // state-changing original (e.g. a POST) is not replayed just to measure
        // it; only re-send when nothing was captured.
        int baseStatus = 0, baseLen = 0;
        if (baseline.hasResponse()) {
            baseStatus = baseline.response().statusCode();
            baseLen = bodyLength(baseline.response());
        } else {
            report(progress, "Sending baseline request…");
            try {
                HttpResponse r = api.http().sendRequest(original).response();
                if (r != null) { baseStatus = r.statusCode(); baseLen = bodyLength(r); }
            } catch (RuntimeException e) {
                api.logging().logToError("Baseline request failed: " + e.getMessage());
            }
        }

        int n = 0;
        for (ProbeGenerator.Probe probe : probes) {
            n++;
            report(progress, "Probe " + n + "/" + probes.size() + ": " + probe.label);
            try {
                HttpRequest modified = applyProbe(original, originalBody, paramName, source, probe);
                HttpResponse r = api.http().sendRequest(modified).response();
                int status = r == null ? 0 : r.statusCode();
                int len = r == null ? 0 : bodyLength(r);
                String respBody = r == null ? "" : r.bodyToString();
                boolean reflected = probe.value != null && probe.value.length() >= 3
                        && respBody.contains(probe.value);
                boolean sqlError = com.assessmentnotebook.analyze.SqlErrorSignature.matches(respBody);

                ParameterTest t = new ParameterTest();
                t.parameterName = paramName;
                t.source = source;
                t.pageId = pageId;
                t.parameterId = parameterId;
                t.probeKind = probe.kind.name();
                t.probeLabel = probe.label;
                t.sentValue = probe.omit ? "(omitted)" : probe.value;
                t.baselineStatus = baseStatus;
                t.baselineLength = baseLen;
                t.responseStatus = status;
                t.responseLength = len;
                t.reflected = reflected;
                t.sqlErrorSignature = sqlError;
                out.add(t);
            } catch (RuntimeException e) {
                api.logging().logToError("Probe " + probe.label + " failed: " + e.getMessage());
            }
        }
        report(progress, "Saving " + out.size() + " result(s)…");
        controller.recordParameterTests(out);
        return out;
    }

    /** Backwards-compatible entry point without progress reporting. */
    public List<ParameterTest> run(HttpRequestResponse baseline, String paramName,
            ParameterTest.Source source, List<ProbeGenerator.Probe> probes,
            String pageId, String parameterId) throws IOException {
        return run(baseline, paramName, source, probes, pageId, parameterId, null);
    }

    private static void report(Consumer<String> progress, String line) {
        if (progress != null) progress.accept(line);
    }

    private HttpRequest applyProbe(HttpRequest original, String originalBody, String name,
            ParameterTest.Source source, ProbeGenerator.Probe probe) {
        switch (source) {
            case JSON: {
                String value = probe.omit ? "" : probe.value;
                return original.withBody(JsonParameters.setAtPath(originalBody, name, value));
            }
            case BODY: {
                if (probe.omit) {
                    return original.withRemovedParameters(
                            HttpParameter.parameter(name, "", HttpParameterType.BODY));
                }
                return original.withUpdatedParameters(
                        HttpParameter.bodyParameter(name, encode(probe.value)));
            }
            case QUERY:
            default: {
                if (probe.omit) {
                    return original.withRemovedParameters(
                            HttpParameter.parameter(name, "", HttpParameterType.URL));
                }
                return original.withUpdatedParameters(
                        HttpParameter.urlParameter(name, encode(probe.value)));
            }
        }
    }

    /** Burp inserts parameter values verbatim, so encode them for the URL/body. */
    private String encode(String value) {
        if (value == null) return "";
        try {
            return api.utilities().urlUtils().encode(value);
        } catch (RuntimeException e) {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static int bodyLength(HttpResponse r) {
        try {
            return r.body() == null ? 0 : r.body().length();
        } catch (RuntimeException e) {
            return r.bodyToString().length();
        }
    }
}
