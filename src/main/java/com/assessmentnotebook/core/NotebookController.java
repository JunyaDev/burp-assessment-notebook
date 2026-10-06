package com.assessmentnotebook.core;

import com.assessmentnotebook.analyze.DiscoveredPage;
import com.assessmentnotebook.analyze.JsonParameters;
import com.assessmentnotebook.analyze.ReflectionDetector;
import com.assessmentnotebook.analyze.ResponseShape;
import com.assessmentnotebook.analyze.UrlTemplates;
import com.assessmentnotebook.graph.AppGraph;
import com.assessmentnotebook.html.HtmlGenerator;
import com.assessmentnotebook.model.*;
import com.assessmentnotebook.store.ProjectLayout;
import com.assessmentnotebook.store.ProjectStore;
import com.assessmentnotebook.store.Timestamps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The engine that ties the model, persistence, graph and HTML generation
 * together and performs every mutating operation the Burp UI exposes. It has no
 * dependency on Burp, so the whole registration and documentation workflow is
 * unit-testable without a running Burp instance.
 *
 * <p>Every mutation goes through {@link #saveAndGenerate()} so disk and docs
 * never drift from the in-memory model. Registration is additive and
 * de-duplicating: registering the same URL twice updates the existing page.
 */
public final class NotebookController {
    private final ProjectStore store;
    private final ProjectLayout layout;
    private final ReflectionDetector reflectionDetector = new ReflectionDetector();
    private final com.assessmentnotebook.analyze.TechnologyDetector techDetector =
            new com.assessmentnotebook.analyze.TechnologyDetector();
    private Project project;
    /** Set view of {@code project.relationships} so de-duplication is O(1). */
    private final java.util.Set<Relationship> relationshipIndex = new java.util.HashSet<>();

    // Auto-capture state. Captures mutate the model without saving; the documents
    // they touched are remembered here until flushCapture() writes them once.
    private boolean captureDirty;
    private final java.util.Set<String> touchedPages = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> touchedForms = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> touchedResources = new java.util.LinkedHashSet<>();
    /** Pages by "METHOD templateKey" and by templateKey alone; see ensureCaptureIndex(). */
    private java.util.Map<String, Page> captureIndex;
    private java.util.Map<String, Page> captureIndexAnyMethod;
    private int captureIndexedPages = -1;
    private boolean captureIndexCollapse;
    private java.util.Map<String, Resource> captureResources;
    private int captureResourcesFor = -1;

    public NotebookController(Path root) {
        this.store = new ProjectStore(root);
        this.layout = store.layout();
    }

    // ---- lifecycle -------------------------------------------------------

    public Project create(String name) throws IOException {
        project = store.create(name);
        saveAndGenerate();
        return project;
    }

    public Project open() throws IOException {
        project = store.load();
        // Projects written before auto-capture existed (or hand-edited) lack these.
        if (project.capture == null) project.capture = new CaptureConfig();
        if (project.capture.rules == null) project.capture.rules = new ArrayList<>();
        return project;
    }

    public boolean exists() { return store.exists(); }
    public Project project() { return project; }
    public ProjectLayout layout() { return layout; }
    public AppGraph graph() { return new AppGraph(project); }

    /** Persist the model and rebuild all documentation from it. */
    public synchronized void saveAndGenerate() throws IOException {
        resolveLinkDestinations(null);
        store.save(project);
        new HtmlGenerator(project, layout).generateAll();
        // Everything is on disk and rebuilt, including whatever auto-capture had pending.
        clearCapturePending();
    }

    private void clearCapturePending() {
        captureDirty = false;
        touchedPages.clear();
        touchedForms.clear();
        touchedResources.clear();
    }

    /**
     * Persist the model and regenerate only the documents named by
     * {@code scope}. Used by operations that cannot change the application
     * structure (notes, annotations, probe results, findings, ...), so a large
     * project does not pay for a full rebuild on every small edit. Anything that
     * adds a page or resolves links still goes through {@link #saveAndGenerate()}.
     */
    private void saveAndGenerate(Regen scope) throws IOException {
        store.save(project);
        scope.run(new HtmlGenerator(project, layout));
    }

    @FunctionalInterface
    private interface Regen { void run(HtmlGenerator g) throws IOException; }

    /** Documents that show notes for an entity: the index plus the entity's own page. */
    private static Regen docFor(EntityType type, String id) {
        return g -> { g.generateIndex(); g.generateDocFor(type, id); };
    }

    /** Forms that own a parameter (a parameter's tests render on its form document). */
    private List<Form> formsContaining(String parameterId) {
        List<Form> out = new ArrayList<>();
        if (parameterId == null) return out;
        for (Form f : project.forms) {
            if (f.parameterIds.contains(parameterId)) out.add(f);
        }
        return out;
    }

    // ---- page registration ----------------------------------------------

    /** Register or update a page from a tester-confirmed proposal. */
    public synchronized Page registerPage(PageRegistration reg) throws IOException {
        Page page = registerPageInternal(reg, Timestamps.now(), false);
        saveAndGenerate();
        return page;
    }

    /**
     * Apply a registration to the model without saving. {@code templated} is
     * set by auto-capture, which groups concrete URLs under one record and so
     * wants the page to remember its path template.
     */
    private Page registerPageInternal(PageRegistration reg, String now, boolean templated)
            throws IOException {
        Page page = project.findPageByRequest(reg.url, reg.method);
        boolean isNew = page == null;
        if (isNew) {
            page = new Page();
            page.id = project.nextId(EntityType.PAGE);
            page.url = reg.url;
            page.method = reg.method;
            page.firstSeen = now;
            if (templated) page.pathTemplate = UrlTemplates.templatePath(reg.url);
            project.pages.add(page);
        }
        page.kind = reg.kind == null ? Page.Kind.PAGE : reg.kind;
        page.lastSeen = now;
        if (reg.statusCode != 0) page.statusCode = reg.statusCode;
        if (!reg.contentType.isBlank()) page.contentType = reg.contentType;
        if (isNew || reg.discoverySourceKind != DiscoverySource.MANUAL) {
            page.discoverySourceKind = reg.discoverySourceKind;
        }
        if (!reg.discoverySource.isBlank()) page.discoverySource = reg.discoverySource;
        if (reg.discovered != null && !reg.discovered.title.isBlank()) {
            page.title = reg.discovered.title;
        }

        // Preserve raw traffic and the page source as evidence.
        RequestRecord request = null;
        if (reg.rawRequest != null && reg.rawRequest.length > 0) {
            request = recordRequest(reg, page);
        }
        if (reg.rawResponse != null && reg.rawResponse.length > 0) {
            String rawFile = store.saveRaw(reg.rawResponse, baseName(page, "response"), ".txt");
            ResponseRecord resp = new ResponseRecord();
            resp.id = project.nextId(EntityType.RESPONSE);
            resp.statusCode = reg.statusCode;
            resp.reasonPhrase = reg.reasonPhrase;
            resp.contentType = reg.contentType;
            if (reg.responseHeaders != null) resp.headers = new ArrayList<>(reg.responseHeaders);
            resp.rawFile = rawFile;
            resp.createdAt = now;
            project.responses.add(resp);
            if (request != null) {
                request.responseId = resp.id;
                relate(EntityType.REQUEST, request.id, Relationship.PRODUCES_RESPONSE,
                        EntityType.RESPONSE, resp.id, "");
            }
        }
        if (reg.pageSource != null && !reg.pageSource.isBlank()) {
            // JSON is re-indented for reading; the untouched bytes are in the raw response.
            String source = JsonParameters.pretty(reg.pageSource);
            if (!sameAsLastSource(page, source)) {
                String srcFile = store.saveSource(source, sourceFileName(page, reg.pageSource));
                if (!page.sourceFiles.contains(srcFile)) page.sourceFiles.add(srcFile);
            }
        }

        // Re-registration updates what is already there rather than adding a
        // second copy: forms match on action+method+identifier, links on
        // destination+element type, resources on canonical URL.
        DiscoveredPage d = reg.discovered != null ? reg.discovered : new DiscoveredPage();
        for (DiscoveredPage.DiscoveredForm df : d.forms) registerForm(page, df, now);
        for (DiscoveredPage.DiscoveredLink dl : d.links) registerLink(page, dl, now);
        java.util.Map<String, Resource> resourceIndex = resourceIndex();
        for (DiscoveredPage.DiscoveredResource dr : d.resources) {
            registerResource(page, dr, now, resourceIndex);
        }

        // Discovery relationship from a parent page, if provided.
        if (reg.parentPageId != null && !reg.parentPageId.equals(page.id)
                && project.findPage(reg.parentPageId) != null) {
            relate(EntityType.PAGE, reg.parentPageId, Relationship.DISCOVERED,
                    EntityType.PAGE, page.id, reg.discoverySource);
        }

        // Auto-detect that this page was reached via a redirect from a page we
        // already captured (spec §3): scan captured 3xx responses whose Location
        // resolves to this page's URL.
        detectRedirectDiscovery(page);

        // Automatic technology detection from this response (spec §5).
        if (reg.responseHeaders != null || (reg.pageSource != null && !reg.pageSource.isBlank())) {
            mergeDetections(techDetector.detect(reg.url, reg.responseHeaders,
                    reg.pageSource, reg.contentType), now);
        }

        // What the endpoint returns, and which page's scripts call it.
        mergeResponseFields(page, ResponseShape.jsonFields(reg.pageSource));
        if (page.kind == Page.Kind.API) {
            Page caller = pageForReferer(reg.requestHeaders);
            if (caller != null && caller != page && caller.kind == Page.Kind.PAGE) {
                relate(EntityType.PAGE, caller.id, Relationship.CALLS,
                        EntityType.PAGE, page.id, "");
                touchedPages.add(caller.id);
            }
        }

        // Remember what this capture looked like, so auto-capture can tell a
        // repeat sighting from a page that now answers differently.
        String fingerprint = CaptureFingerprint.of(reg).key();
        if (page.fingerprints.isEmpty()) {
            page.fingerprints.add(fingerprint);
            if (page.variantIds.isEmpty()) page.baseline = baselineFrom(reg, page, now);
        } else if (!page.fingerprints.contains(fingerprint)) {
            page.fingerprints.add(fingerprint);
        }
        noteObservedValues(page, RequestParams.of(reg));
        return page;
    }

    private RequestRecord recordRequest(PageRegistration reg, Page page) throws IOException {
        String rawFile = store.saveRaw(reg.rawRequest, baseName(page, "request"), ".txt");
        RequestRecord req = new RequestRecord();
        req.id = project.nextId(EntityType.REQUEST);
        req.method = reg.method;
        req.url = reg.url;
        req.host = reg.host;
        req.port = reg.port;
        req.secure = reg.secure;
        if (reg.requestHeaders != null) req.headers = new ArrayList<>(reg.requestHeaders);
        req.body = reg.requestBody == null ? "" : reg.requestBody;
        req.rawFile = rawFile;
        req.createdAt = Timestamps.now();
        project.requests.add(req);
        relate(EntityType.PAGE, page.id, Relationship.RELATES_TO,
                EntityType.REQUEST, req.id, "request that fetched this page");
        return req;
    }

    /** Create the form on the page, or update the existing form it matches. */
    private Form registerForm(Page page, DiscoveredPage.DiscoveredForm df, String now) {
        Form form = findFormOnPage(page, df);
        if (form == null) {
            form = new Form();
            form.id = project.nextId(EntityType.FORM);
            form.pageId = page.id;
            form.action = nz(df.action);
            form.method = nz(df.method);
            form.formIdentifier = nz(df.identifier);
            form.createdAt = now;
            project.forms.add(form);
            page.formIds.add(form.id);
            relate(EntityType.PAGE, page.id, Relationship.CONTAINS_FORM,
                    EntityType.FORM, form.id, "");
        }
        form.encType = nz(df.encType);
        form.updatedAt = now;
        for (DiscoveredPage.DiscoveredInput in : df.inputs) {
            Parameter p = findParameterOnForm(form, in.name, in.type);
            if (p == null) {
                p = new Parameter();
                p.id = project.nextId(EntityType.PARAMETER);
                p.name = nz(in.name);
                p.createdAt = now;
                project.parameters.add(p);
                form.parameterIds.add(p.id);
                relate(EntityType.FORM, form.id, Relationship.HAS_PARAMETER,
                        EntityType.PARAMETER, p.id, "");
            }
            p.inputType = nz(in.type);
            p.defaultValue = nz(in.value);
            p.required = in.required;
            p.updatedAt = now;
        }
        return form;
    }

    private Form findFormOnPage(Page page, DiscoveredPage.DiscoveredForm df) {
        // The synthesized request-parameter form uses the request URL as its
        // action, which varies with the query string; a page has one such form
        // per kind, so it is matched on identifier and method alone.
        boolean requestForm = nz(df.identifier).startsWith(REQUEST_FORM);
        for (String fid : page.formIds) {
            Form f = project.findForm(fid);
            if (f != null && (requestForm || nz(f.action).equals(nz(df.action)))
                    && nz(f.method).equalsIgnoreCase(nz(df.method))
                    && nz(f.formIdentifier).equals(nz(df.identifier))) {
                return f;
            }
        }
        return null;
    }

    private Parameter findParameterOnForm(Form form, String name, String type) {
        // Unnamed controls (e.g. a submit button) are matched by type instead.
        for (String pid : form.parameterIds) {
            Parameter p = project.findParameter(pid);
            if (p == null) continue;
            if (!nz(name).isEmpty() ? nz(p.name).equals(nz(name))
                    : nz(p.name).isEmpty() && nz(p.inputType).equals(nz(type))) {
                return p;
            }
        }
        return null;
    }

    /** Create the link on the page, or refresh the existing link it matches. */
    private Link registerLink(Page page, DiscoveredPage.DiscoveredLink dl, String now) {
        Link link = null;
        for (String lid : page.linkIds) {
            Link l = project.findLink(lid);
            if (l != null && nz(l.destinationUrl).equals(nz(dl.url))
                    && nz(l.elementType).equals(nz(dl.elementType))
                    && nz(l.method).equalsIgnoreCase(nz(dl.method))) {
                link = l;
                break;
            }
        }
        if (link == null) {
            link = new Link();
            link.id = project.nextId(EntityType.LINK);
            link.sourcePageId = page.id;
            link.destinationUrl = nz(dl.url);
            link.elementType = nz(dl.elementType);
            link.method = nz(dl.method);
            link.discoveryMethod = "page analysis";
            link.createdAt = now;
            project.links.add(link);
            page.linkIds.add(link.id);
            relate(EntityType.PAGE, page.id, Relationship.CONTAINS_LINK,
                    EntityType.LINK, link.id, "");
        }
        link.visibleText = nz(dl.text);
        link.updatedAt = now;
        return link;
    }

    /** True if the page's most recently saved source has exactly this content. */
    private boolean sameAsLastSource(Page page, String source) {
        if (page.sourceFiles.isEmpty()) return false;
        try {
            Path last = layout.root.resolve(page.sourceFiles.get(page.sourceFiles.size() - 1));
            return java.nio.file.Files.exists(last)
                    && java.nio.file.Files.readString(last, StandardCharsets.UTF_8).equals(source);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Identifier prefix of the form synthesized from a request's own parameters. */
    private static final String REQUEST_FORM = "request parameters";

    private static String nz(String s) { return s == null ? "" : s; }

    private Resource registerResource(Page page, DiscoveredPage.DiscoveredResource dr,
            String now, java.util.Map<String, Resource> index) throws IOException {
        Resource res = resolveResource(dr.url, parseType(dr.type), now, index);
        if (!res.pageIds.contains(page.id)) res.pageIds.add(page.id);
        if (!page.resourceIds.contains(res.id)) page.resourceIds.add(res.id);
        relate(EntityType.PAGE, page.id, Relationship.LOADS_RESOURCE,
                EntityType.RESOURCE, res.id, "");
        // If the tester captured the body, save it as this resource's source copy
        // (once): re-registration with identical bytes does not write a duplicate.
        if (dr.body != null && !dr.body.isBlank() && !sameAsResourceSource(res, dr.body)) {
            res.sourceFile = store.saveSource(dr.body, resourceSourceFileName(res));
            String prov = dr.bodySource == null || dr.bodySource.isBlank()
                    ? "" : " (" + dr.bodySource + ")";
            res.notes = appendLine(res.notes, now + "  body captured" + prov);
            res.updatedAt = now;
        }
        return res;
    }

    /**
     * Find the resource with this URL's canonical identity, or create it. A
     * resource has ONE identity regardless of how many pages load it or how the
     * URL was spelled (spec §11); the raw form is recorded, and a more specific
     * type upgrades a generic one (spec §10).
     */
    private Resource resolveResource(String rawUrl, Resource.Type type, String now,
            java.util.Map<String, Resource> index) {
        String canonical = com.assessmentnotebook.analyze.ResourceUrls.canonical(rawUrl);
        Resource res = index.get(canonical);
        if (res == null) {
            res = new Resource();
            res.id = project.nextId(EntityType.RESOURCE);
            res.type = type;
            res.url = canonical;
            res.createdAt = now;
            project.resources.add(res);
            index.put(canonical, res);
        } else if (Resource.specificity(type) > Resource.specificity(res.type)) {
            res.type = type; // upgrade OTHER -> a specific type
        }
        if (rawUrl != null && !rawUrl.isBlank() && !res.observedUrls.contains(rawUrl)) {
            res.observedUrls.add(rawUrl);
        }
        res.updatedAt = now;
        return res;
    }

    /** Existing resources keyed by canonical URL (stored URLs are already canonical). */
    private java.util.Map<String, Resource> resourceIndex() {
        java.util.Map<String, Resource> m = new java.util.HashMap<>();
        for (Resource r : project.resources) {
            String key = r.url == null ? "" : r.url;
            m.putIfAbsent(key, r);
            String canon = com.assessmentnotebook.analyze.ResourceUrls.canonical(r.url);
            if (!canon.equals(key)) m.putIfAbsent(canon, r); // legacy, non-canonical record
        }
        return m;
    }

    // ---- other entities --------------------------------------------------

    public synchronized Technology addTechnology(Technology.Category cat, String name,
            String version, String evidence, String notes) throws IOException {
        String now = Timestamps.now();
        Technology t = new Technology();
        t.id = project.nextId(EntityType.TECHNOLOGY);
        t.category = cat;
        t.name = name;
        t.version = version == null ? "" : version;
        t.evidence = evidence == null ? "" : evidence;
        t.confidence = Technology.Confidence.HIGH; // a hand-added entry is trusted
        t.userEdited = true;
        t.notes = notes == null ? "" : notes;
        t.firstObserved = t.lastUpdated = now;
        t.createdAt = t.updatedAt = now;
        project.technologies.add(t);
        saveAndGenerate(HtmlGenerator::generateIndex);
        return t;
    }

    /**
     * Merge automatic detections into the technology list (spec §5). An existing
     * technology is updated in place: a more specific version and a higher
     * confidence overwrite the old values (unless the tester has edited the
     * entry), new evidence is appended, and every change is logged to history so
     * a duplicate is never created. Returns the affected records.
     */
    public synchronized List<Technology> recordDetections(
            List<com.assessmentnotebook.analyze.TechnologyDetector.Detection> detections)
            throws IOException {
        List<Technology> touched = mergeDetections(detections, Timestamps.now());
        if (!touched.isEmpty()) saveAndGenerate(HtmlGenerator::generateIndex);
        return touched;
    }

    /** Merge detections into the model without persisting (used during registration). */
    private List<Technology> mergeDetections(
            List<com.assessmentnotebook.analyze.TechnologyDetector.Detection> detections,
            String now) {
        List<Technology> touched = new ArrayList<>();
        for (var d : detections) {
            Technology t = findTechnologyByName(d.name);
            if (t == null) {
                t = new Technology();
                t.id = project.nextId(EntityType.TECHNOLOGY);
                t.category = d.category;
                t.name = d.name;
                t.version = d.version;
                t.confidence = d.confidence;
                t.firstObserved = now;
                t.createdAt = now;
                t.history.add(now + "  detected via " + d.evidenceSource
                        + (d.version.isEmpty() ? "" : " (v" + d.version + ")"));
                project.technologies.add(t);
            } else {
                if (!t.userEdited) {
                    if (isMoreSpecificVersion(d.version, t.version)) {
                        t.history.add(now + "  version: "
                                + (t.version.isEmpty() ? "(unknown)" : t.version)
                                + " -> " + d.version + " via " + d.evidenceSource);
                        t.version = d.version;
                    }
                    if (d.confidence.ordinal() > t.confidence.ordinal()) {
                        t.history.add(now + "  confidence: " + t.confidence.label
                                + " -> " + d.confidence.label);
                        t.confidence = d.confidence;
                    }
                    if (t.category == Technology.Category.OTHER
                            && d.category != Technology.Category.OTHER) {
                        t.category = d.category;
                    }
                }
            }
            addEvidence(t, d.evidenceSource, d.evidenceDetail, now);
            t.lastUpdated = t.updatedAt = now;
            if (!touched.contains(t)) touched.add(t);
        }
        return touched;
    }

    /** Manually correct a technology record (spec §6); marks it tester-owned. */
    public synchronized Technology updateTechnology(String id, Technology.Category cat, String name,
            String version, Technology.Confidence confidence, String notes) throws IOException {
        Technology t = project.findTechnology(id);
        if (t == null) return null;
        String now = Timestamps.now();
        if (cat != null && cat != t.category) {
            t.history.add(now + "  category: " + t.category.label + " -> " + cat.label + " (edited)");
            t.category = cat;
        }
        if (name != null && !name.equals(t.name)) {
            t.history.add(now + "  name: " + t.name + " -> " + name + " (edited)");
            t.name = name;
        }
        if (version != null && !version.equals(t.version)) {
            t.history.add(now + "  version: " + (t.version.isEmpty() ? "(unknown)" : t.version)
                    + " -> " + (version.isEmpty() ? "(unknown)" : version) + " (edited)");
            t.version = version;
        }
        if (confidence != null && confidence != t.confidence) {
            t.history.add(now + "  confidence: " + t.confidence.label + " -> "
                    + confidence.label + " (edited)");
            t.confidence = confidence;
        }
        if (notes != null) t.notes = notes;
        t.userEdited = true;
        t.lastUpdated = t.updatedAt = now;
        saveAndGenerate(HtmlGenerator::generateIndex);
        return t;
    }

    /** Remove a technology (e.g. a false positive; spec §6). */
    public synchronized boolean deleteTechnology(String id) throws IOException {
        boolean removed = project.technologies.removeIf(t -> id.equals(t.id));
        if (removed) saveAndGenerate(HtmlGenerator::generateIndex);
        return removed;
    }

    private Technology findTechnologyByName(String name) {
        if (name == null) return null;
        for (Technology t : project.technologies) {
            if (t.name != null && t.name.equalsIgnoreCase(name.trim())) return t;
        }
        return null;
    }

    private static void addEvidence(Technology t, String source, String detail, String now) {
        for (Technology.Evidence e : t.evidences) {
            if (java.util.Objects.equals(e.source, source)
                    && java.util.Objects.equals(e.detail, detail)) return;
        }
        t.evidences.add(new Technology.Evidence(source, detail, now));
    }

    /** True if {@code candidate} is a strictly more specific version than {@code current}. */
    public static boolean isMoreSpecificVersion(String candidate, String current) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (current == null || current.isEmpty()) return true;
        if (candidate.equals(current)) return false;
        if (candidate.startsWith(current + ".")) return true;   // 18 -> 18.3.1
        if (current.startsWith(candidate + ".")) return false;  // 18.3.1 -> 18
        // Otherwise prefer the one with more dotted components.
        int cand = candidate.split("\\.").length;
        int cur = current.split("\\.").length;
        return cand > cur;
    }

    /**
     * Record the interesting items found by scanning a JavaScript resource
     * (endpoints, secrets, dangerous calls, exports, bypass hints). The script is
     * resolved (or created) as a SCRIPT resource by URL, its body is saved as the
     * captured source if provided, and each selected finding is stored and linked
     * to the resource — which is itself linked to the pages that load it, so the
     * findings surface both on the script's page and on every page that loads it.
     * Findings duplicate-safe on (resource, kind, value).
     */
    public synchronized List<JsFinding> recordJsFindings(String scriptUrl, String pageId,
            String jsBody, List<JsFinding> findings) throws IOException {
        String now = Timestamps.now();
        Resource res = resolveResource(scriptUrl, Resource.Type.SCRIPT, now, resourceIndex());
        Page page = pageId == null ? null : project.findPage(pageId);
        if (page != null) {
            if (!res.pageIds.contains(page.id)) res.pageIds.add(page.id);
            if (!page.resourceIds.contains(res.id)) page.resourceIds.add(res.id);
            relate(EntityType.PAGE, page.id, Relationship.LOADS_RESOURCE,
                    EntityType.RESOURCE, res.id, "");
        }
        if (jsBody != null && !jsBody.isBlank() && !sameAsResourceSource(res, jsBody)) {
            res.sourceFile = store.saveSource(jsBody, resourceSourceFileName(res));
        }
        List<JsFinding> saved = new ArrayList<>();
        for (JsFinding f : findings) {
            f.resourceId = res.id;
            if (jsFindingExists(res.id, f.kind, f.value)) continue;
            f.id = project.nextId(EntityType.JS_FINDING);
            f.timestamp = now;
            project.jsFindings.add(f);
            relate(EntityType.RESOURCE, res.id, Relationship.RELATES_TO,
                    EntityType.JS_FINDING, f.id, "js finding");
            saved.add(f);
        }
        final String rid = res.id;
        final List<String> pages = new ArrayList<>(res.pageIds);
        saveAndGenerate(g -> {
            g.generateIndex();
            g.generateResource(rid);
            for (String pid : pages) g.generatePage(pid);
        });
        return saved;
    }

    private boolean jsFindingExists(String resourceId, JsFinding.Kind kind, String value) {
        for (JsFinding f : project.jsFindings) {
            if (resourceId.equals(f.resourceId) && f.kind == kind
                    && java.util.Objects.equals(f.value, value)) return true;
        }
        return false;
    }

    /** Register a resource on its own (e.g. an API/XHR endpoint), not via a page. */
    public synchronized Resource addResource(String url, Resource.Type type) throws IOException {
        return addResource(url, type, "", null);
    }

    /**
     * Register or update a resource, optionally associating it with a page and
     * attaching notes. Deduplicates on canonical URL (spec §10, §11): calling
     * this for a script already discovered from a page returns the same record
     * and adds the association rather than creating a duplicate.
     */
    public synchronized Resource addResource(String url, Resource.Type type,
            String notes, String associatedPageId) throws IOException {
        String now = Timestamps.now();
        Resource r = resolveResource(url, type, now, resourceIndex());
        if (notes != null && !notes.isBlank()) {
            r.notes = r.notes.isBlank() ? notes : r.notes + "\n" + notes;
        }
        if (associatedPageId != null) {
            Page page = project.findPage(associatedPageId);
            if (page != null) {
                if (!r.pageIds.contains(page.id)) r.pageIds.add(page.id);
                if (!page.resourceIds.contains(r.id)) page.resourceIds.add(r.id);
                relate(EntityType.PAGE, page.id, Relationship.LOADS_RESOURCE,
                        EntityType.RESOURCE, r.id, "");
            }
        }
        final Resource fr = r;
        final String pid = associatedPageId;
        saveAndGenerate(g -> { g.generateResource(fr.id); if (pid != null) g.generatePage(pid); });
        return r;
    }

    /** Compute suggested project metadata from captured traffic (spec §4). */
    public com.assessmentnotebook.analyze.ProjectMetadataInference.Suggestion suggestProjectMetadata() {
        return com.assessmentnotebook.analyze.ProjectMetadataInference.infer(project);
    }

    /** Persist the tester-confirmed project setup (spec §4). */
    public synchronized void applyProjectSetup(TargetInfo info) throws IOException {
        project.target = info;
        saveAndGenerate(HtmlGenerator::generateIndex);
    }

    public synchronized Note addNote(EntityType type, String id, Note.Kind kind, String text)
            throws IOException {
        Note n = new Note();
        n.id = project.nextId(EntityType.NOTE);
        n.targetType = type;
        n.targetId = id;
        n.kind = kind;
        n.text = text;
        n.createdAt = n.updatedAt = Timestamps.now();
        project.notes.add(n);
        saveAndGenerate(docFor(type, id));
        return n;
    }

    // ---- parameter tests (spec §14-§18) ----------------------------------

    /**
     * Record one probe result, characterizing it conservatively (never
     * auto-confirmed) and linking it to its parameter/page/request so it becomes
     * permanent project knowledge (spec §18).
     */
    public synchronized ParameterTest recordParameterTest(ParameterTest t) throws IOException {
        recordParameterTests(List.of(t));
        return t;
    }

    /**
     * Record a whole probe run with a single save and a regeneration limited
     * to the documents that show the results (the parameter's form(s) and the
     * page). A test with no parameter id is linked to the page's form parameter
     * of the same name when there is exactly one, so it appears in the docs.
     */
    public synchronized List<ParameterTest> recordParameterTests(List<ParameterTest> tests)
            throws IOException {
        java.util.Set<String> pages = new java.util.LinkedHashSet<>();
        java.util.Set<String> forms = new java.util.LinkedHashSet<>();
        for (ParameterTest t : tests) {
            t.id = project.nextId(EntityType.PARAM_TEST);
            t.timestamp = Timestamps.now();
            com.assessmentnotebook.analyze.ParameterAnalysis.characterize(t);
            if (t.parameterId == null && t.pageId != null) {
                t.parameterId = parameterIdByName(t.pageId, t.parameterName);
            }
            project.parameterTests.add(t);
            if (t.parameterId != null) {
                relate(EntityType.PARAMETER, t.parameterId, Relationship.RELATES_TO,
                        EntityType.PARAM_TEST, t.id, t.probeLabel);
                for (Form f : formsContaining(t.parameterId)) forms.add(f.id);
            }
            if (t.pageId != null) {
                relate(EntityType.PAGE, t.pageId, Relationship.RELATES_TO,
                        EntityType.PARAM_TEST, t.id, "parameter test");
                pages.add(t.pageId);
            }
        }
        saveAndGenerate(g -> {
            for (String id : forms) g.generateForm(id);
            for (String id : pages) g.generatePage(id);
        });
        return tests;
    }

    /** The id of the single form parameter with this name on the page, else null. */
    private String parameterIdByName(String pageId, String name) {
        Page page = project.findPage(pageId);
        if (page == null || name == null || name.isEmpty()) return null;
        String found = null;
        for (String fid : page.formIds) {
            Form f = project.findForm(fid);
            if (f == null) continue;
            for (String pid : f.parameterIds) {
                Parameter p = project.findParameter(pid);
                if (p != null && name.equals(p.name)) {
                    if (found != null && !found.equals(p.id)) return null; // ambiguous
                    found = p.id;
                }
            }
        }
        return found;
    }

    /** All recorded tests for a parameter. */
    public List<ParameterTest> parameterTestsFor(String parameterId) {
        List<ParameterTest> out = new ArrayList<>();
        for (ParameterTest t : project.parameterTests) {
            if (parameterId != null && parameterId.equals(t.parameterId)) out.add(t);
        }
        return out;
    }

    /** Conservative behavior summary for a parameter (spec §14). */
    public com.assessmentnotebook.analyze.ParameterAnalysis.Summary summarizeParameter(
            String parameterId) {
        return com.assessmentnotebook.analyze.ParameterAnalysis.summarize(
                parameterTestsFor(parameterId));
    }

    /** Promote a probe result the tester judges real into a finding (spec §16). */
    public synchronized Vulnerability promoteTestToFinding(String testId, String title,
            Vulnerability.Severity severity) throws IOException {
        ParameterTest t = null;
        for (ParameterTest x : project.parameterTests) {
            if (x.id.equals(testId)) { t = x; break; }
        }
        if (t == null) return null;
        Vulnerability v = new Vulnerability();
        v.id = project.nextId(EntityType.VULNERABILITY);
        v.title = title == null || title.isBlank()
                ? "Parameter behavior: " + t.parameterName : title;
        v.severity = severity == null ? Vulnerability.Severity.INFO : severity;
        v.technicalObservation = "Probe " + t.probeLabel + " on " + t.parameterName
                + " (" + t.source + "): " + t.observation;
        v.createdAt = v.updatedAt = Timestamps.now();
        project.vulnerabilities.add(v);
        t.classification = ParameterTest.Classification.CONFIRMED_VULNERABILITY;
        t.promotedVulnId = v.id;
        if (t.parameterId != null) {
            relate(EntityType.VULNERABILITY, v.id, Relationship.EVIDENCED_BY,
                    EntityType.PARAM_TEST, t.id, "");
            relate(EntityType.VULNERABILITY, v.id, Relationship.AFFECTS,
                    EntityType.PARAMETER, t.parameterId, "");
        }
        saveAndGenerate();
        return v;
    }

    // ---- page variants (spec §12, §13) -----------------------------------

    /**
     * Register a captured variant of a page (same URL, specific request
     * conditions). Derived descriptors are computed once here so later
     * comparison needs only the model.
     */
    public synchronized PageVariant registerVariant(PageVariant v, String responseBody)
            throws IOException {
        String now = Timestamps.now();
        // Group variants by the page's PATH (ignoring the query string), so
        // /search?q=apple and /search?q=admin are variants of one /search page
        // even when the page was first registered with a query.
        Page page = pageByPath(v.url, v.method);
        if (page == null) page = pageByPathAnyMethod(v.url);
        boolean newPage = page == null;
        if (newPage) {
            page = new Page();
            page.id = project.nextId(EntityType.PAGE);
            page.url = v.url;
            page.method = v.method;
            page.firstSeen = now;
            page.discoverySourceKind = DiscoverySource.MANUAL;
            project.pages.add(page);
        }
        page.lastSeen = now;
        addVariant(page, v, responseBody, now);
        if (newPage) {
            saveAndGenerate();
        } else {
            final String pid = page.id;
            saveAndGenerate(g -> g.generatePage(pid));
        }
        return v;
    }

    /** Attach a variant to a page (ids, derived descriptors, saved body) without saving. */
    private void addVariant(Page page, PageVariant v, String responseBody, String now)
            throws IOException {
        v.id = project.nextId(EntityType.PAGE_VARIANT);
        v.pageId = page.id;
        v.timestamp = now;
        if (responseBody != null) {
            describeResponse(v, responseBody);
            v.sourceFile = store.saveSource(JsonParameters.pretty(responseBody),
                    page.id + "-" + v.id + bodyExtension(v.contentType, responseBody));
        }
        project.variants.add(v);
        if (!page.variantIds.contains(v.id)) page.variantIds.add(v.id);
        relate(EntityType.PAGE, page.id, Relationship.RELATES_TO,
                EntityType.PAGE_VARIANT, v.id, "variant");
    }

    /** Compute the descriptors variants are compared on (spec §13). */
    private static void describeResponse(PageVariant v, String body) {
        v.bodyLength = body.length();
        v.responseFields = ResponseShape.jsonFields(body);
        // jsoup would wrap a JSON body in an empty document; it has no title or tags.
        boolean json = !v.responseFields.isEmpty();
        v.title = json ? "" : ResponseShape.title(body);
        v.structureSignature = ResponseShape.structureSignature(body);
        v.reflectedInputNames = reflectedInputs(v.allInputs(), body);
    }

    /** The request conditions of a registration, as a (not yet attached) variant. */
    private static PageVariant variantFrom(PageRegistration reg, String label) {
        RequestParams rp = RequestParams.of(reg);
        PageVariant v = new PageVariant();
        v.url = reg.url;
        v.method = reg.method;
        v.label = label;
        v.queryParams.putAll(rp.query);
        v.bodyParams.putAll(rp.body);
        v.jsonParams.putAll(rp.json);
        v.cookies.putAll(rp.cookies);
        v.authContext = rp.authorized ? "Authorization header present" : "anonymous";
        if (!rp.contentType.isEmpty()) v.relevantHeaders.add("Content-Type: " + rp.contentType);
        if (rp.authorized) v.relevantHeaders.add("Authorization: present");
        v.statusCode = reg.statusCode;
        v.contentType = nz(reg.contentType);
        return v;
    }

    /** A page's first capture, kept on the page until there is something to compare it to. */
    private static PageVariant baselineFrom(PageRegistration reg, Page page, String now) {
        PageVariant b = variantFrom(reg, "baseline (first capture)");
        b.timestamp = now;
        if (reg.pageSource != null && !reg.pageSource.isBlank()) {
            describeResponse(b, reg.pageSource);
            if (!page.sourceFiles.isEmpty()) {
                b.sourceFile = page.sourceFiles.get(page.sourceFiles.size() - 1);
            }
        }
        return b;
    }

    /** Turn the page's held first capture into a real variant, if it still has one. */
    private void promoteBaseline(Page page) {
        PageVariant b = page.baseline;
        if (b == null) return;
        page.baseline = null;
        b.id = project.nextId(EntityType.PAGE_VARIANT);
        b.pageId = page.id;
        project.variants.add(b);
        page.variantIds.add(b.id);
        relate(EntityType.PAGE, page.id, Relationship.RELATES_TO,
                EntityType.PAGE_VARIANT, b.id, "variant");
    }

    /** Compare every variant of a page against the first (baseline). */
    public List<com.assessmentnotebook.analyze.VariantDiff.Comparison> compareVariants(String pageId) {
        List<PageVariant> vs = new ArrayList<>();
        for (PageVariant v : project.variants) {
            if (pageId.equals(v.pageId)) vs.add(v);
        }
        return com.assessmentnotebook.analyze.VariantDiff.compareToBaseline(vs);
    }

    /** A page whose URL has the same scheme/host/path (query ignored) and method. */
    private Page pageByPath(String url, String method) {
        String key = pathKey(url);
        for (Page p : project.pages) {
            if (pathKey(p.url).equals(key) && p.method.equalsIgnoreCase(method)) return p;
        }
        return null;
    }

    private Page pageByPathAnyMethod(String url) {
        String key = pathKey(url);
        for (Page p : project.pages) {
            if (pathKey(p.url).equals(key)) return p;
        }
        return null;
    }

    /** scheme://host[:port]/path, lower-cased, no query or fragment. */
    static String pathKey(String url) {
        if (url == null) return "";
        try {
            java.net.URI u = java.net.URI.create(url.trim());
            if (u.getScheme() == null || u.getHost() == null) {
                int q = url.indexOf('?');
                return q >= 0 ? url.substring(0, q) : url;
            }
            String path = u.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            int port = u.getPort();
            boolean def = ("http".equalsIgnoreCase(u.getScheme()) && port == 80)
                    || ("https".equalsIgnoreCase(u.getScheme()) && port == 443);
            return u.getScheme().toLowerCase() + "://" + u.getHost().toLowerCase()
                    + (port > 0 && !def ? ":" + port : "") + path;
        } catch (RuntimeException e) {
            int q = url.indexOf('?');
            return q >= 0 ? url.substring(0, q) : url;
        }
    }

    private static List<String> reflectedInputs(java.util.Map<String, String> inputs, String body) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        for (var e : inputs.entrySet()) {
            String val = e.getValue();
            if (val != null && val.length() >= 3 && body.contains(val)) out.add(e.getKey());
        }
        return out;
    }

    // ---- interesting strings / wordlists (spec §8) -----------------------

    /** Mark a string as interesting for later wordlists. Deduplicates per bucket. */
    public synchronized InterestingString addInterestingString(String value,
            InterestingString.Category category, String sourcePageId, String sourceElement,
            String context, String notes) throws IOException {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return null;
        for (InterestingString s : project.interestingStrings) {
            if (s.category == category && s.value.equals(trimmed)) return s; // already have it
        }
        InterestingString s = new InterestingString();
        s.id = project.nextId(EntityType.INTERESTING_STRING);
        s.value = trimmed;
        s.category = category == null ? InterestingString.Category.GENERAL : category;
        s.sourcePageId = sourcePageId;
        s.sourceElement = sourceElement == null ? "" : sourceElement;
        s.context = context == null ? "" : context;
        s.notes = notes == null ? "" : notes;
        s.timestamp = Timestamps.now();
        project.interestingStrings.add(s);
        if (sourcePageId != null) {
            Page p = project.findPage(sourcePageId);
            if (p != null) {
                relate(EntityType.PAGE, p.id, Relationship.RELATES_TO,
                        EntityType.INTERESTING_STRING, s.id, "interesting string");
            }
        }
        saveAndGenerate(g -> { g.generateIndex(); g.generateWordlistsIndex(); });
        return s;
    }

    /** Distinct, sorted values in one wordlist category. */
    public java.util.List<String> wordlist(InterestingString.Category category) {
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        for (InterestingString s : project.interestingStrings) {
            if (s.category == category) set.add(s.value);
        }
        return new ArrayList<>(set);
    }

    /**
     * Write each non-empty category to {@code wordlists/<slug>.txt} for use by
     * other authorized tools, and return the paths written.
     */
    public synchronized java.util.List<String> exportWordlists() throws IOException {
        layout.ensureDirectories();
        java.util.List<String> written = new ArrayList<>();
        for (InterestingString.Category cat : InterestingString.Category.values()) {
            java.util.List<String> values = wordlist(cat);
            if (values.isEmpty()) continue;
            Path out = layout.wordlists().resolve(cat.slug + ".txt");
            java.nio.file.Files.write(out, values, StandardCharsets.UTF_8);
            written.add(layout.relative(out));
        }
        return written;
    }

    public synchronized Vulnerability createVulnerability(String title, Vulnerability.Severity sev,
            String affectedUrl) throws IOException {
        Vulnerability v = new Vulnerability();
        v.title = title;
        v.severity = sev;
        v.affectedUrl = affectedUrl;
        return createVulnerability(v);
    }

    /**
     * Persist a fully-formed finding (all fields set by the caller), assign its
     * id/timestamps, and link it to a page at the same URL if one exists. Used
     * by the expanded "Create Vulnerability" dialog so every documented field —
     * component, description, technical observation, steps, impact, remediation,
     * status, notes — is captured, not just title/severity/URL.
     */
    public synchronized Vulnerability createVulnerability(Vulnerability v) throws IOException {
        v.id = project.nextId(EntityType.VULNERABILITY);
        if (v.severity == null) v.severity = Vulnerability.Severity.INFO;
        if (v.status == null) v.status = Vulnerability.Status.OPEN;
        v.createdAt = v.updatedAt = Timestamps.now();
        project.vulnerabilities.add(v);
        // Link the finding to a page at the same URL, if one exists.
        Page page = firstPageByUrl(v.affectedUrl);
        if (page != null) {
            relate(EntityType.VULNERABILITY, v.id, Relationship.AFFECTS,
                    EntityType.PAGE, page.id, "");
        }
        final String affectedPageId = page != null ? page.id : null;
        saveAndGenerate(g -> {
            g.generateIndex();
            g.generateDocFor(EntityType.VULNERABILITY, v.id);
            if (affectedPageId != null) g.generatePage(affectedPageId);
        });
        return v;
    }

    public synchronized Interaction addInteraction(String pageId, String action,
            String observedBehavior, String requestId) throws IOException {
        Interaction a = new Interaction();
        a.id = project.nextId(EntityType.INTERACTION);
        a.pageId = pageId;
        a.action = action;
        a.observedBehavior = observedBehavior;
        a.requestId = requestId;
        a.createdAt = Timestamps.now();
        project.interactions.add(a);
        Page p = project.findPage(pageId);
        if (p != null && !p.interactionIds.contains(a.id)) p.interactionIds.add(a.id);
        saveAndGenerate(g -> g.generatePage(pageId));
        return a;
    }

    public synchronized Screenshot addScreenshot(String pageId, byte[] png,
            String stateDescription, int sequence, String interactionId) throws IOException {
        String rel = store.saveScreenshot(png, "shot-" + pageId + "-" + sequence);
        Screenshot s = new Screenshot();
        s.id = project.nextId(EntityType.SCREENSHOT);
        s.pageId = pageId;
        s.imageFile = rel;
        s.stateDescription = stateDescription;
        s.sequence = sequence;
        s.relatedInteractionId = interactionId;
        s.timestamp = Timestamps.now();
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(png));
            if (img != null) { s.imageWidth = img.getWidth(); s.imageHeight = img.getHeight(); }
        } catch (Exception ignored) { /* dimensions optional */ }
        project.screenshots.add(s);
        Page p = project.findPage(pageId);
        if (p != null && !p.screenshotIds.contains(s.id)) p.screenshotIds.add(s.id);
        if (interactionId != null) {
            Interaction act = project.findInteraction(interactionId);
            if (act != null) act.screenshotId = s.id;
        }
        saveAndGenerate(g -> g.generatePage(pageId));
        return s;
    }

    /** Add a rectangle annotation to a screenshot (spec §9). */
    public synchronized Annotation addAnnotation(String screenshotId, int x, int y,
            int width, int height, String label, String description, String relatedRequestId)
            throws IOException {
        Annotation a = new Annotation();
        a.x = x; a.y = y; a.width = width; a.height = height;
        a.label = label == null ? "" : label;
        a.description = description == null ? "" : description;
        a.relatedRequestId = relatedRequestId;
        List<Annotation> added = addAnnotations(screenshotId, List.of(a));
        return added.isEmpty() ? null : added.get(0);
    }

    /**
     * Add several annotations to one screenshot with a single save and a
     * regeneration of just the owning page. Ids and timestamps are assigned here.
     */
    public synchronized List<Annotation> addAnnotations(String screenshotId,
            List<Annotation> annotations) throws IOException {
        Screenshot shot = project.findScreenshot(screenshotId);
        if (shot == null || annotations.isEmpty()) return List.of();
        String now = Timestamps.now();
        for (Annotation a : annotations) {
            a.id = project.nextId(EntityType.ANNOTATION);
            if (a.label == null) a.label = "";
            if (a.description == null) a.description = "";
            a.timestamp = now;
            shot.annotations.add(a);
        }
        final String pageId = shot.pageId;
        saveAndGenerate(g -> { if (pageId != null) g.generatePage(pageId); });
        return annotations;
    }

    /** Attach a typed edge, ignoring exact duplicates. */
    public synchronized void relate(EntityType fromType, String fromId, String kind,
            EntityType toType, String toId, String detail) {
        Relationship r = new Relationship(fromType, fromId, kind, toType, toId);
        r.detail = detail == null ? "" : detail;
        // Rebuild the set view if the list was changed behind our back
        // (tests and tooling append to project.relationships directly).
        if (relationshipIndex.size() != project.relationships.size()) {
            relationshipIndex.clear();
            relationshipIndex.addAll(project.relationships);
        }
        if (relationshipIndex.add(r)) {
            r.createdAt = Timestamps.now();
            project.relationships.add(r);
        }
    }

    /** Run reflection detection for a value against a response. */
    public List<Reflection> detectReflections(String body, List<String> headers,
            String contentType, String value) {
        return reflectionDetector.detect(body, headers, contentType, value);
    }

    public synchronized void attachReflections(String parameterId, List<Reflection> reflections)
            throws IOException {
        Parameter p = project.findParameter(parameterId);
        if (p == null) return;
        p.reflections.addAll(reflections);
        p.updatedAt = Timestamps.now();
        saveAndGenerate();
    }

    // ---- auto-capture (rules) --------------------------------------------

    /** A private copy of the auto-capture settings, for editing. */
    public synchronized CaptureConfig captureConfig() {
        return project.capture.copy();
    }

    /** Replace the auto-capture settings and persist them. */
    public synchronized void applyCaptureConfig(CaptureConfig config) throws IOException {
        project.capture = config.copy();
        store.save(project);
    }

    /**
     * Register an observed page or API endpoint on behalf of an auto-capture
     * rule, <b>without saving</b>; call {@link #flushCapture()} after a batch.
     *
     * <p>A page is identified by method and path (query ignored, id-like
     * segments collapsed when the project says so). The first sighting
     * registers it in full. A later sighting is compared by
     * {@link CaptureFingerprint}: if it matches something already documented it
     * is dropped; if it differs it becomes a page variant, and whatever new
     * forms, links, resources or response fields it shows are merged in.
     */
    public synchronized CaptureResult capture(PageRegistration reg) throws IOException {
        String now = Timestamps.now();
        boolean api = reg.kind == Page.Kind.API;
        Page page = pageByTemplate(reg.method, reg.url);
        if (page == null) {
            if (!api && reg.parentPageId == null) {
                Page referrer = pageForReferer(reg.requestHeaders);
                if (referrer != null) reg.parentPageId = referrer.id;
            }
            page = registerPageInternal(reg, now, project.capture.collapseIds);
            touch(page);
            return new CaptureResult(api ? CaptureResult.Outcome.NEW_ENDPOINT
                    : CaptureResult.Outcome.NEW_PAGE, page.id, reg.method + " " + reg.url);
        }

        CaptureFingerprint fp = CaptureFingerprint.of(reg);
        String key = fp.key();
        RequestParams params = RequestParams.of(reg);
        if (page.fingerprints.isEmpty()) {
            // Registered before fingerprints existed: take this sighting as the
            // baseline rather than guessing whether it differs from the original.
            page.fingerprints.add(key);
            if (page.variantIds.isEmpty()) page.baseline = baselineFrom(reg, page, now);
            noteObservedValues(page, params);
            captureDirty = true;
            return new CaptureResult(CaptureResult.Outcome.DUPLICATE, page.id, "adopted as baseline");
        }
        if (page.fingerprints.contains(key)) {
            if (noteObservedValues(page, params)) captureDirty = true;
            return new CaptureResult(CaptureResult.Outcome.DUPLICATE, page.id, "");
        }
        if (page.variantIds.size() >= Math.max(1, project.capture.maxVariantsPerPage)) {
            return new CaptureResult(CaptureResult.Outcome.CAPPED, page.id,
                    "variant limit reached for " + page.url);
        }

        promoteBaseline(page);
        PageVariant v = variantFrom(reg, CaptureFingerprint.label(page.fingerprints.get(0), key));
        v.auto = true;
        addVariant(page, v, reg.pageSource == null ? "" : reg.pageSource, now);
        page.fingerprints.add(key);
        page.lastSeen = now;

        // A variant often reveals more of the page: merge it, never duplicate.
        DiscoveredPage d = reg.discovered != null ? reg.discovered : new DiscoveredPage();
        for (DiscoveredPage.DiscoveredForm df : d.forms) registerForm(page, df, now);
        for (DiscoveredPage.DiscoveredLink dl : d.links) registerLink(page, dl, now);
        java.util.Map<String, Resource> index = resourceIndex();
        for (DiscoveredPage.DiscoveredResource dr : d.resources) {
            registerResource(page, dr, now, index);
        }
        mergeResponseFields(page, v.responseFields);
        noteObservedValues(page, params);
        touch(page);
        return new CaptureResult(CaptureResult.Outcome.VARIANT, v.id, v.label);
    }

    /**
     * Register an observed static resource on behalf of an auto-capture rule,
     * without saving. A resource already in the notebook is left alone unless
     * this sighting adds something: a more specific type, or another page
     * (taken from the Referer) that loads it.
     */
    public synchronized CaptureResult captureResource(String url, Resource.Type type,
            List<String> requestHeaders) {
        String now = Timestamps.now();
        java.util.Map<String, Resource> index = captureResourceIndex();
        Resource existing = index.get(com.assessmentnotebook.analyze.ResourceUrls.canonical(url));
        Page loader = pageForReferer(requestHeaders);
        boolean isNew = existing == null;
        boolean changed = isNew;
        if (!isNew && Resource.specificity(type) > Resource.specificity(existing.type)) changed = true;
        if (!isNew && loader != null && !existing.pageIds.contains(loader.id)) changed = true;
        if (!changed) return new CaptureResult(CaptureResult.Outcome.DUPLICATE, existing.id, "");

        Resource res = resolveResource(url, type, now, index);
        captureResourcesFor = project.resources.size();
        if (loader != null && !res.pageIds.contains(loader.id)) {
            res.pageIds.add(loader.id);
            if (!loader.resourceIds.contains(res.id)) loader.resourceIds.add(res.id);
            relate(EntityType.PAGE, loader.id, Relationship.LOADS_RESOURCE,
                    EntityType.RESOURCE, res.id, "");
            touchedPages.add(loader.id);
        }
        touchedResources.add(res.id);
        captureDirty = true;
        return new CaptureResult(isNew ? CaptureResult.Outcome.NEW_RESOURCE
                : CaptureResult.Outcome.UPDATED, res.id, res.url);
    }

    /**
     * Persist what {@link #capture} and {@link #captureResource} changed since
     * the last flush and regenerate only the documents involved. Returns false
     * when there was nothing to write.
     */
    public synchronized boolean flushCapture() throws IOException {
        if (!captureDirty) return false;
        resolveLinkDestinations(touchedPages);
        store.save(project);
        HtmlGenerator g = new HtmlGenerator(project, layout);
        g.generateIndex();
        for (String id : touchedPages) g.generatePage(id);
        for (String id : touchedForms) g.generateForm(id);
        for (String id : touchedResources) g.generateResource(id);
        if (!touchedPages.isEmpty()) g.generateLinksIndex();
        clearCapturePending();
        return true;
    }

    /** Mark a page and everything rendered from it for the next flush. */
    private void touch(Page page) {
        captureDirty = true;
        touchedPages.add(page.id);
        touchedForms.addAll(page.formIds);
        touchedResources.addAll(page.resourceIds);
    }

    /**
     * The page a request belongs to, for actions that start from a request the
     * tester selected: an exact URL match first, then the record auto-capture
     * would file it under (so {@code /api/users/42} finds {@code /api/users/{id}}).
     */
    public synchronized Page findPageFor(String url, String method) {
        Page exact = project.findPageByRequest(url, method);
        if (exact != null) return exact;
        Page templated = pageByTemplate(method, url);
        if (templated != null) return templated;
        for (Page p : project.pages) {
            if (p.url.equals(url)) return p;
        }
        ensureCaptureIndex();
        return captureIndexAnyMethod.get(UrlTemplates.key(url, captureIndexCollapse));
    }

    /** Compute something from the model while no capture or edit is mutating it. */
    public synchronized <T> T read(java.util.function.Function<Project, T> view) {
        return view.apply(project);
    }

    private Page pageByTemplate(String method, String url) {
        ensureCaptureIndex();
        return captureIndex.get(nz(method).toUpperCase() + " "
                + UrlTemplates.key(url, captureIndexCollapse));
    }

    /** The registered page a Referer header points at, or null. */
    private Page pageForReferer(List<String> requestHeaders) {
        String referer = RequestParams.header(requestHeaders, "referer");
        if (referer.isEmpty()) return null;
        Page get = pageByTemplate("GET", referer);
        if (get != null) return get;
        return captureIndexAnyMethod.get(UrlTemplates.key(referer, captureIndexCollapse));
    }

    /** (Re)build the page lookup when pages were added or the grouping rule changed. */
    private void ensureCaptureIndex() {
        boolean collapse = project.capture.collapseIds;
        if (captureIndex != null && captureIndexedPages == project.pages.size()
                && captureIndexCollapse == collapse) {
            return;
        }
        captureIndex = new java.util.HashMap<>();
        captureIndexAnyMethod = new java.util.HashMap<>();
        for (Page p : project.pages) {
            String key = UrlTemplates.key(p.url, collapse);
            captureIndex.putIfAbsent(nz(p.method).toUpperCase() + " " + key, p);
            captureIndexAnyMethod.putIfAbsent(key, p);
        }
        captureIndexedPages = project.pages.size();
        captureIndexCollapse = collapse;
    }

    /** {@link #resourceIndex()}, kept between captures while no resource is added elsewhere. */
    private java.util.Map<String, Resource> captureResourceIndex() {
        if (captureResources == null || captureResourcesFor != project.resources.size()) {
            captureResources = resourceIndex();
            captureResourcesFor = project.resources.size();
        }
        return captureResources;
    }

    private static final int MAX_RESPONSE_FIELDS = 300;
    private static final int MAX_OBSERVED_VALUES = 8;
    /** Parameters whose values are credentials, never copied into observed values. */
    private static final java.util.regex.Pattern SENSITIVE_NAME = java.util.regex.Pattern.compile(
            "(?i)pass(word|wd)?|pwd|secret|token|api[_-]?key|otp|(^|_)pin$|cvv|card|ssn|authorization");

    private void mergeResponseFields(Page page, List<String> fields) {
        for (String f : fields) {
            if (page.responseFields.size() >= MAX_RESPONSE_FIELDS) return;
            if (!page.responseFields.contains(f)) page.responseFields.add(f);
        }
    }

    /**
     * Record the values this request sent for the page's request parameters (a
     * few distinct examples each), so the form document shows what a parameter
     * actually carries. Returns true if anything was added.
     */
    private boolean noteObservedValues(Page page, RequestParams params) {
        java.util.Map<String, String> sent = params.all();
        if (sent.isEmpty()) return false;
        boolean added = false;
        for (String fid : page.formIds) {
            Form f = project.findForm(fid);
            if (f == null || !nz(f.formIdentifier).startsWith(REQUEST_FORM)) continue;
            for (String pid : f.parameterIds) {
                Parameter p = project.findParameter(pid);
                if (p == null) continue;
                String value = sent.get(p.name);
                if (value == null || value.isEmpty() || value.length() > 120
                        || SENSITIVE_NAME.matcher(nz(p.name)).find()
                        || p.observedValues.size() >= MAX_OBSERVED_VALUES
                        || p.observedValues.contains(value)) {
                    continue;
                }
                p.observedValues.add(value);
                touchedForms.add(f.id);
                added = true;
            }
        }
        return added;
    }

    // ---- parameter annotation --------------------------------------------

    /** A tester's description of one parameter; a null field is left as it is. */
    public static final class ParameterEdit {
        public final String parameterId;
        public final String purpose;
        public final String notes;

        public ParameterEdit(String parameterId, String purpose, String notes) {
            this.parameterId = parameterId;
            this.purpose = purpose;
            this.notes = notes;
        }
    }

    /**
     * Save the tester's purpose and notes for parameters, and notes for forms
     * (form id -> text), then redraw the forms and pages that show them.
     * Returns how many parameters and forms actually changed.
     */
    public synchronized int annotateParameters(List<ParameterEdit> edits,
            java.util.Map<String, String> formNotes) throws IOException {
        String now = Timestamps.now();
        java.util.Set<String> forms = new java.util.LinkedHashSet<>();
        int changed = 0;
        for (ParameterEdit e : edits == null ? List.<ParameterEdit>of() : edits) {
            Parameter p = project.findParameter(e.parameterId);
            if (p == null) continue;
            boolean diff = false;
            if (e.purpose != null && !e.purpose.equals(p.purpose)) { p.purpose = e.purpose; diff = true; }
            if (e.notes != null && !e.notes.equals(p.notes)) { p.notes = e.notes; diff = true; }
            if (!diff) continue;
            p.updatedAt = now;
            changed++;
            for (Form f : formsContaining(p.id)) forms.add(f.id);
        }
        if (formNotes != null) {
            for (var e : formNotes.entrySet()) {
                Form f = project.findForm(e.getKey());
                if (f == null || e.getValue() == null || e.getValue().equals(f.notes)) continue;
                f.notes = e.getValue();
                f.updatedAt = now;
                changed++;
                forms.add(f.id);
            }
        }
        if (changed == 0) return 0;
        java.util.Set<String> pages = new java.util.LinkedHashSet<>();
        for (String fid : forms) {
            Form f = project.findForm(fid);
            if (f != null && f.pageId != null) pages.add(f.pageId);
        }
        saveAndGenerate(g -> {
            for (String id : forms) g.generateForm(id);
            for (String id : pages) g.generatePage(id);
        });
        return changed;
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * Fill in destinationPageId + links-to edges for links whose target is a
     * page. Source pages whose links were resolved are added to
     * {@code affectedPages} (when given) so a partial regeneration can redraw them.
     */
    private void resolveLinkDestinations(java.util.Set<String> affectedPages) {
        PageLookup pages = new PageLookup(project);
        for (Link l : project.links) {
            if (l.destinationPageId != null) continue;
            Page p = pages.find(l.destinationUrl);
            if (p == null) continue;
            l.destinationPageId = p.id;
            if (l.sourcePageId != null) {
                if (affectedPages != null) affectedPages.add(l.sourcePageId);
                relate(EntityType.PAGE, l.sourcePageId, Relationship.LINKS_TO,
                        EntityType.PAGE, p.id, "");
            }
        }
    }

    /**
     * If a page we already captured returned a 3xx whose Location points at
     * {@code target}, record a redirect edge from that source page and mark the
     * target's discovery as an HTTP redirect (unless the tester set something
     * more specific than a plain manual/direct value).
     */
    private void detectRedirectDiscovery(Page target) {
        String targetCanon = com.assessmentnotebook.analyze.ResourceUrls.canonical(target.url);
        for (ResponseRecord resp : project.responses) {
            if (resp.statusCode < 300 || resp.statusCode >= 400) continue;
            String location = headerValue(resp.headers, "location");
            if (location.isEmpty()) continue;
            String resolved = resolveAgainst(target.url, location);
            if (!com.assessmentnotebook.analyze.ResourceUrls.canonical(resolved)
                    .equals(targetCanon)) continue;
            Page source = pageOwningResponse(resp.id);
            if (source == null || source.id.equals(target.id)) continue;
            relate(EntityType.PAGE, source.id, Relationship.REDIRECTS_TO,
                    EntityType.PAGE, target.id, "HTTP " + resp.statusCode + " redirect");
            if (target.discoverySourceKind == DiscoverySource.MANUAL
                    || target.discoverySourceKind == DiscoverySource.DIRECT_NAVIGATION) {
                target.discoverySourceKind = DiscoverySource.HTTP_REDIRECT;
                if (target.discoverySource.isBlank()) {
                    target.discoverySource = "redirected from " + source.url;
                }
            }
        }
    }

    /** The page that owns the request which produced the given response, if any. */
    private Page pageOwningResponse(String responseId) {
        RequestRecord owningReq = null;
        for (RequestRecord req : project.requests) {
            if (responseId.equals(req.responseId)) { owningReq = req; break; }
        }
        if (owningReq == null) return null;
        for (Relationship r : project.relationships) {
            if (r.fromType == EntityType.PAGE && r.toType == EntityType.REQUEST
                    && owningReq.id.equals(r.toId)) {
                return project.findPage(r.fromId);
            }
        }
        return null;
    }

    private static String headerValue(List<String> headers, String name) {
        if (headers == null) return "";
        String want = name.toLowerCase() + ":";
        for (String line : headers) {
            if (line != null && line.toLowerCase().startsWith(want)) {
                int colon = line.indexOf(':');
                return colon >= 0 ? line.substring(colon + 1).trim() : "";
            }
        }
        return "";
    }

    private static String resolveAgainst(String base, String location) {
        try {
            return java.net.URI.create(base).resolve(location.trim()).toString();
        } catch (RuntimeException e) {
            return location.trim();
        }
    }

    private Page firstPageByUrl(String url) {
        if (url == null) return null;
        for (Page p : project.pages) if (url.equals(p.url)) return p;
        return null;
    }

    private static Resource.Type parseType(String s) {
        try {
            return Resource.Type.valueOf(s == null ? "OTHER" : s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Resource.Type.OTHER;
        }
    }

    private static String baseName(Page page, String suffix) {
        return page.id + "-" + suffix;
    }

    /** True if this resource's saved source copy already has exactly this content. */
    private boolean sameAsResourceSource(Resource res, String body) {
        if (res.sourceFile == null || res.sourceFile.isBlank()) return false;
        try {
            Path f = layout.root.resolve(res.sourceFile);
            return java.nio.file.Files.exists(f)
                    && java.nio.file.Files.readString(f, StandardCharsets.UTF_8).equals(body);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** File name for a saved resource body: "<id>-<leaf>", extension from the URL. */
    private static String resourceSourceFileName(Resource res) {
        String leaf = "resource";
        try {
            String path = java.net.URI.create(res.url).getPath();
            if (path != null && !path.isEmpty() && !path.endsWith("/")) {
                leaf = path.substring(path.lastIndexOf('/') + 1);
            }
        } catch (RuntimeException ignored) { /* keep default */ }
        if (leaf.isBlank()) leaf = "resource";
        return res.id + "-" + leaf;
    }

    /** Append a line to a notes field, keeping existing content. */
    private static String appendLine(String existing, String line) {
        if (existing == null || existing.isBlank()) return line;
        return existing + "\n" + line;
    }

    private static String sourceFileName(Page page, String body) {
        String leaf = "index";
        try {
            String path = java.net.URI.create(page.url).getPath();
            if (path != null && !path.isEmpty() && !path.endsWith("/")) {
                leaf = path.substring(path.lastIndexOf('/') + 1);
            }
        } catch (RuntimeException ignored) { /* keep default */ }
        if (leaf.isBlank()) leaf = "index";
        if (!leaf.contains(".")) leaf = leaf + bodyExtension(page.contentType, body);
        return page.id + "-" + leaf;
    }

    /** File extension for a saved body, so it is highlighted as what it is. */
    private static String bodyExtension(String contentType, String body) {
        String ct = nz(contentType).toLowerCase();
        String start = body == null ? "" : body.stripLeading();
        if (ct.contains("html")) return ".html";
        if (ct.contains("json") || start.startsWith("{") || start.startsWith("[")) return ".json";
        if (ct.contains("xml")) return ".xml";
        if (ct.contains("text/plain")) return ".txt";
        return ".html";
    }

    /** Bytes of a UTF-8 string, for callers assembling raw evidence. */
    public static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }
}
