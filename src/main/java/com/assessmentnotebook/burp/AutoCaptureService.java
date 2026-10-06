package com.assessmentnotebook.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import com.assessmentnotebook.analyze.RuleMatcher;
import com.assessmentnotebook.analyze.TrafficClassifier;
import com.assessmentnotebook.core.CaptureResult;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.CaptureConfig;
import com.assessmentnotebook.model.CaptureRule;
import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watches traffic and registers what the project's auto-capture rules select,
 * so the notebook fills in as the tester browses instead of one right-click at
 * a time. It is strictly passive: it never sends a request and never alters
 * one passing through.
 *
 * <p>The HTTP handler runs on Burp's own threads for every response, so it only
 * tests the rules against already-parsed values and queues the matches. One
 * background thread does the real work in batches — a page load is a burst of
 * dozens of responses, and each batch costs a single save and one regeneration
 * of the documents it touched.
 */
public final class AutoCaptureService implements HttpHandler {
    /** Matches waiting to be registered; beyond this, new ones are dropped and counted. */
    private static final int QUEUE_LIMIT = 2000;
    private static final int BATCH_LIMIT = 200;
    /** Bodies larger than this are recorded by URL only, not parsed or saved. */
    private static final int MAX_BODY = 5_000_000;

    /** Running totals since the extension loaded, for the tab's status line. */
    public static final class Stats {
        public final AtomicInteger pages = new AtomicInteger();
        public final AtomicInteger endpoints = new AtomicInteger();
        public final AtomicInteger resources = new AtomicInteger();
        public final AtomicInteger variants = new AtomicInteger();
        /** Already documented, ignored by a rule, or over a page's variant limit. */
        public final AtomicInteger skipped = new AtomicInteger();
        public final AtomicInteger dropped = new AtomicInteger();
        public final AtomicInteger errors = new AtomicInteger();
    }

    private static final class Item {
        /** The project whose rule selected this exchange; it is registered nowhere else. */
        final NotebookController project;
        final HttpRequestResponse rr;
        final CaptureRule.Action action;
        final String tool;

        Item(NotebookController project, HttpRequestResponse rr, CaptureRule.Action action,
                String tool) {
            this.project = project;
            this.rr = rr;
            this.action = action;
            this.tool = tool;
        }
    }

    private final MontoyaApi api;
    private final NotebookSession session;
    private final RequestExtractor extractor = new RequestExtractor();
    private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>(QUEUE_LIMIT);
    private final Stats stats = new Stats();
    private final Thread worker;
    private volatile boolean running = true;
    private volatile boolean handlerErrorLogged;

    public AutoCaptureService(MontoyaApi api, NotebookSession session) {
        this.api = api;
        this.session = session;
        worker = new Thread(this::drain, "assessment-notebook-capture");
        worker.setDaemon(true);
        worker.start();
    }

    public Stats stats() { return stats; }
    public int pending() { return queue.size(); }

    /** Stop the worker; called when the extension is unloaded. */
    public void shutdown() {
        running = false;
        worker.interrupt();
    }

    // ---- HTTP handler (Burp's threads: stay cheap, never throw) -----------

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        return RequestToBeSentAction.continueWith(request);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        try {
            consider(response.initiatingRequest(), response, response.toolSource().toolType(),
                    false);
        } catch (RuntimeException e) {
            // Whatever goes wrong here must not disturb the tester's traffic.
            if (!handlerErrorLogged) {
                handlerErrorLogged = true;
                api.logging().logToError("Assessment Notebook: auto-capture could not inspect a "
                        + "response (further occurrences are not logged)", e);
            }
        }
        return ResponseReceivedAction.continueWith(response);
    }

    /**
     * Queue the exchange if a rule selects it. {@code replay} is used for proxy
     * history: it applies the rules even while live capture is switched off and
     * waits for queue space instead of dropping.
     *
     * @return true when the exchange was queued for registration
     */
    private boolean consider(HttpRequest request, HttpResponse response, ToolType tool,
            boolean replay) {
        NotebookController controller = session.controller();
        if (controller == null || request == null || response == null) return false;
        CaptureConfig config = controller.project().capture;
        if (config == null || config.rules.isEmpty() || (!config.enabled && !replay)) {
            return false;
        }
        RuleMatcher.Facts facts = new RuleMatcher.Facts();
        facts.fromProxy = tool == ToolType.PROXY;
        facts.fromRepeater = tool == ToolType.REPEATER;
        if (!facts.fromProxy && !facts.fromRepeater) return false;
        final String url = request.url();
        facts.url = url;
        facts.method = request.method();
        facts.status = response.statusCode();
        String contentType = response.headerValue("Content-Type");
        facts.contentType = contentType == null ? "" : contentType;
        facts.inScope = () -> api.scope().isInScope(url);

        CaptureRule rule = RuleMatcher.firstMatch(config.rules, facts);
        if (rule == null) return false;
        if (rule.action == CaptureRule.Action.IGNORE) {
            stats.skipped.incrementAndGet();
            return false;
        }
        Item item = new Item(controller, HttpRequestResponse.httpRequestResponse(request, response),
                rule.action, tool.toolName());
        if (replay) {
            try {
                queue.put(item);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } else if (!queue.offer(item)) {
            stats.dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    // ---- proxy history ---------------------------------------------------

    /** Result of {@link #replayProxyHistory}: how much history there was and how much matched. */
    public static final class ReplayResult {
        public final int examined;
        public final int queued;

        ReplayResult(int examined, int queued) {
            this.examined = examined;
            this.queued = queued;
        }
    }

    /**
     * Run the current rules over everything already in Proxy history, oldest
     * first, so traffic browsed before the rules existed is registered too.
     * Blocks while the queue is full; call it off the Swing thread.
     */
    public ReplayResult replayProxyHistory() {
        int examined = 0;
        int queued = 0;
        for (ProxyHttpRequestResponse h : api.proxy().history()) {
            if (!running || Thread.currentThread().isInterrupted()) break;
            if (!h.hasResponse()) continue;
            examined++;
            if (consider(h.finalRequest(), h.response(), ToolType.PROXY, true)) queued++;
        }
        return new ReplayResult(examined, queued);
    }

    // ---- worker ----------------------------------------------------------

    private void drain() {
        List<Item> batch = new ArrayList<>();
        while (running) {
            try {
                Item first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                // A page load arrives as a burst; give the rest of it time to land.
                Thread.sleep(300);
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, BATCH_LIMIT - 1);
                registerBatch(batch);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                stats.errors.incrementAndGet();
                api.logging().logToError("Assessment Notebook: auto-capture batch failed", e);
            }
        }
    }

    private void registerBatch(List<Item> batch) {
        NotebookController controller = session.controller();
        if (controller == null) return;
        int[] before = totals();
        for (Item item : batch) {
            // Matched under a project that has since been closed: not this one's to keep.
            if (item.project != controller) continue;
            try {
                count(register(controller, item));
            } catch (Exception e) {
                stats.errors.incrementAndGet();
                api.logging().logToError("Assessment Notebook: could not auto-register "
                        + item.rr.request().url(), e);
            }
        }
        try {
            if (controller.flushCapture()) session.fireChanged();
        } catch (Exception e) {
            stats.errors.incrementAndGet();
            api.logging().logToError("Assessment Notebook: could not save auto-captured items", e);
        }
        int[] after = totals();
        if (after[0] + after[1] + after[2] + after[3] > before[0] + before[1] + before[2] + before[3]) {
            api.logging().logToOutput("Assessment Notebook: auto-captured +" + (after[0] - before[0])
                    + " pages, +" + (after[1] - before[1]) + " API endpoints, +"
                    + (after[2] - before[2]) + " resources, +" + (after[3] - before[3])
                    + " variants");
        }
    }

    private int[] totals() {
        return new int[]{stats.pages.get(), stats.endpoints.get(), stats.resources.get(),
                stats.variants.get()};
    }

    private CaptureResult register(NotebookController controller, Item item) throws Exception {
        HttpRequest request = item.rr.request();
        HttpResponse response = item.rr.response();
        List<String> headers = headerLines(request);
        String contentType = response.headerValue("Content-Type");
        TrafficClassifier.Result seen = TrafficClassifier.classify(request.method(), request.url(),
                headers, response.statusCode(), contentType);

        TrafficClassifier.Kind kind;
        switch (item.action) {
            case PAGE: kind = TrafficClassifier.Kind.PAGE; break;
            case API: kind = TrafficClassifier.Kind.API; break;
            case RESOURCE: kind = TrafficClassifier.Kind.RESOURCE; break;
            default: kind = seen.kind;
        }
        // Whatever a rule asks for, a preflight or a bodiless 304 documents nothing
        // as a page; registering one would only add an empty "variant".
        if (kind == TrafficClassifier.Kind.SKIP
                || (kind != TrafficClassifier.Kind.RESOURCE
                        && seen.kind == TrafficClassifier.Kind.SKIP)) {
            return null;
        }
        // A very large body is noted by URL rather than parsed and stored.
        if (kind != TrafficClassifier.Kind.RESOURCE && response.body().length() > MAX_BODY) {
            return controller.captureResource(request.url(), Resource.Type.OTHER, headers);
        }
        if (kind == TrafficClassifier.Kind.RESOURCE) {
            Resource.Type type = seen.kind == TrafficClassifier.Kind.RESOURCE ? seen.resourceType
                    : seen.kind == TrafficClassifier.Kind.API ? Resource.Type.API
                    : Resource.Type.OTHER;
            return controller.captureResource(request.url(), type, headers);
        }
        PageRegistration reg = extractor.toRegistration(item.rr, item.tool);
        reg.kind = kind == TrafficClassifier.Kind.API ? Page.Kind.API : Page.Kind.PAGE;
        return controller.capture(reg);
    }

    private void count(CaptureResult result) {
        if (result == null) {
            stats.skipped.incrementAndGet();
            return;
        }
        switch (result.outcome) {
            case NEW_PAGE: stats.pages.incrementAndGet(); break;
            case NEW_ENDPOINT: stats.endpoints.incrementAndGet(); break;
            case NEW_RESOURCE: stats.resources.incrementAndGet(); break;
            case VARIANT: stats.variants.incrementAndGet(); break;
            case UPDATED: break;
            default: stats.skipped.incrementAndGet();
        }
    }

    private static List<String> headerLines(HttpRequest request) {
        List<String> out = new ArrayList<>();
        for (HttpHeader h : request.headers()) out.add(h.name() + ": " + h.value());
        return out;
    }
}
