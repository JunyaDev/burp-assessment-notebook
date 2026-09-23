package com.assessmentnotebook.core;

import com.assessmentnotebook.analyze.DiscoveredPage;
import com.assessmentnotebook.analyze.ReflectionDetector;
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
    private Project project;

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
        return project;
    }

    public boolean exists() { return store.exists(); }
    public Project project() { return project; }
    public ProjectLayout layout() { return layout; }
    public AppGraph graph() { return new AppGraph(project); }

    /** Persist the model and rebuild all documentation from it. */
    public synchronized void saveAndGenerate() throws IOException {
        resolveLinkDestinations();
        store.save(project);
        new HtmlGenerator(project, layout).generateAll();
    }

    // ---- page registration ----------------------------------------------

    /** Register or update a page from a tester-confirmed proposal. */
    public synchronized Page registerPage(PageRegistration reg) throws IOException {
        String now = Timestamps.now();
        Page page = project.findPageByRequest(reg.url, reg.method);
        boolean isNew = page == null;
        if (isNew) {
            page = new Page();
            page.id = project.nextId(EntityType.PAGE);
            page.url = reg.url;
            page.method = reg.method;
            page.firstSeen = now;
            project.pages.add(page);
        }
        page.lastSeen = now;
        if (reg.statusCode != 0) page.statusCode = reg.statusCode;
        if (!reg.contentType.isBlank()) page.contentType = reg.contentType;
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
            String srcFile = store.saveSource(reg.pageSource, sourceFileName(page));
            if (!page.sourceFiles.contains(srcFile)) page.sourceFiles.add(srcFile);
        }

        DiscoveredPage d = reg.discovered != null ? reg.discovered : new DiscoveredPage();
        for (DiscoveredPage.DiscoveredForm df : d.forms) registerForm(page, df, now);
        for (DiscoveredPage.DiscoveredLink dl : d.links) registerLink(page, dl, now);
        for (DiscoveredPage.DiscoveredResource dr : d.resources) registerResource(page, dr, now);

        // Discovery relationship from a parent page, if provided.
        if (reg.parentPageId != null && !reg.parentPageId.equals(page.id)
                && project.findPage(reg.parentPageId) != null) {
            relate(EntityType.PAGE, reg.parentPageId, Relationship.DISCOVERED,
                    EntityType.PAGE, page.id, reg.discoverySource);
        }

        saveAndGenerate();
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

    private Form registerForm(Page page, DiscoveredPage.DiscoveredForm df, String now) {
        Form form = new Form();
        form.id = project.nextId(EntityType.FORM);
        form.pageId = page.id;
        form.action = df.action;
        form.method = df.method;
        form.encType = df.encType;
        form.formIdentifier = df.identifier;
        form.createdAt = now;
        form.updatedAt = now;
        for (DiscoveredPage.DiscoveredInput in : df.inputs) {
            Parameter p = new Parameter();
            p.id = project.nextId(EntityType.PARAMETER);
            p.name = in.name;
            p.inputType = in.type;
            p.defaultValue = in.value;
            p.required = in.required;
            p.createdAt = now;
            p.updatedAt = now;
            project.parameters.add(p);
            form.parameterIds.add(p.id);
            relate(EntityType.FORM, form.id, Relationship.HAS_PARAMETER,
                    EntityType.PARAMETER, p.id, "");
        }
        project.forms.add(form);
        page.formIds.add(form.id);
        relate(EntityType.PAGE, page.id, Relationship.CONTAINS_FORM,
                EntityType.FORM, form.id, "");
        return form;
    }

    private Link registerLink(Page page, DiscoveredPage.DiscoveredLink dl, String now) {
        Link link = new Link();
        link.id = project.nextId(EntityType.LINK);
        link.sourcePageId = page.id;
        link.destinationUrl = dl.url;
        link.visibleText = dl.text;
        link.elementType = dl.elementType;
        link.method = dl.method;
        link.discoveryMethod = "page analysis";
        link.createdAt = now;
        link.updatedAt = now;
        project.links.add(link);
        page.linkIds.add(link.id);
        relate(EntityType.PAGE, page.id, Relationship.CONTAINS_LINK,
                EntityType.LINK, link.id, "");
        return link;
    }

    private Resource registerResource(Page page, DiscoveredPage.DiscoveredResource dr, String now) {
        Resource.Type type = parseType(dr.type);
        Resource res = null;
        for (Resource existing : project.resources) {
            if (existing.url.equals(dr.url) && existing.type == type) { res = existing; break; }
        }
        if (res == null) {
            res = new Resource();
            res.id = project.nextId(EntityType.RESOURCE);
            res.type = type;
            res.url = dr.url;
            res.createdAt = now;
            project.resources.add(res);
        }
        res.updatedAt = now;
        if (!res.pageIds.contains(page.id)) res.pageIds.add(page.id);
        if (!page.resourceIds.contains(res.id)) page.resourceIds.add(res.id);
        relate(EntityType.PAGE, page.id, Relationship.LOADS_RESOURCE,
                EntityType.RESOURCE, res.id, "");
        return res;
    }

    // ---- other entities --------------------------------------------------

    public synchronized Technology addTechnology(Technology.Category cat, String name,
            String version, String evidence, String notes) throws IOException {
        Technology t = new Technology();
        t.id = project.nextId(EntityType.TECHNOLOGY);
        t.category = cat;
        t.name = name;
        t.version = version;
        t.evidence = evidence;
        t.notes = notes == null ? "" : notes;
        t.createdAt = t.updatedAt = Timestamps.now();
        project.technologies.add(t);
        saveAndGenerate();
        return t;
    }

    /** Register a resource on its own (e.g. an API/XHR endpoint), not via a page. */
    public synchronized Resource addResource(String url, Resource.Type type) throws IOException {
        for (Resource existing : project.resources) {
            if (existing.url.equals(url) && existing.type == type) return existing;
        }
        Resource r = new Resource();
        r.id = project.nextId(EntityType.RESOURCE);
        r.type = type;
        r.url = url;
        r.createdAt = r.updatedAt = Timestamps.now();
        project.resources.add(r);
        saveAndGenerate();
        return r;
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
        saveAndGenerate();
        return n;
    }

    public synchronized Vulnerability createVulnerability(String title, Vulnerability.Severity sev,
            String affectedUrl) throws IOException {
        Vulnerability v = new Vulnerability();
        v.id = project.nextId(EntityType.VULNERABILITY);
        v.title = title;
        v.severity = sev;
        v.affectedUrl = affectedUrl;
        v.createdAt = v.updatedAt = Timestamps.now();
        project.vulnerabilities.add(v);
        // Link the finding to a page at the same URL, if one exists.
        Page page = firstPageByUrl(affectedUrl);
        if (page != null) {
            relate(EntityType.VULNERABILITY, v.id, Relationship.AFFECTS,
                    EntityType.PAGE, page.id, "");
        }
        saveAndGenerate();
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
        saveAndGenerate();
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
        project.screenshots.add(s);
        Page p = project.findPage(pageId);
        if (p != null && !p.screenshotIds.contains(s.id)) p.screenshotIds.add(s.id);
        if (interactionId != null) {
            Interaction act = project.findInteraction(interactionId);
            if (act != null) act.screenshotId = s.id;
        }
        saveAndGenerate();
        return s;
    }

    /** Attach a typed edge, ignoring exact duplicates. */
    public synchronized void relate(EntityType fromType, String fromId, String kind,
            EntityType toType, String toId, String detail) {
        Relationship r = new Relationship(fromType, fromId, kind, toType, toId);
        r.detail = detail == null ? "" : detail;
        if (!project.relationships.contains(r)) {
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

    // ---- helpers ---------------------------------------------------------

    /** Fill in destinationPageId + links-to edges for links whose target is a page. */
    private void resolveLinkDestinations() {
        for (Link l : project.links) {
            if (l.destinationPageId != null) continue;
            for (Page p : project.pages) {
                if (p.url.equals(l.destinationUrl)) {
                    l.destinationPageId = p.id;
                    if (l.sourcePageId != null) {
                        relate(EntityType.PAGE, l.sourcePageId, Relationship.LINKS_TO,
                                EntityType.PAGE, p.id, "");
                    }
                    break;
                }
            }
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

    private static String sourceFileName(Page page) {
        String leaf = "index";
        try {
            String path = java.net.URI.create(page.url).getPath();
            if (path != null && !path.isEmpty() && !path.endsWith("/")) {
                leaf = path.substring(path.lastIndexOf('/') + 1);
            }
        } catch (RuntimeException ignored) { /* keep default */ }
        if (leaf.isBlank()) leaf = "index";
        if (!leaf.contains(".")) leaf = leaf + ".html";
        return page.id + "-" + leaf;
    }

    /** Bytes of a UTF-8 string, for callers assembling raw evidence. */
    public static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }
}
