package com.assessmentnotebook.repair;

import com.assessmentnotebook.analyze.ResourceUrls;
import com.assessmentnotebook.analyze.ResponseShape;
import com.assessmentnotebook.analyze.TrafficClassifier;
import com.assessmentnotebook.analyze.UrlTemplates;
import com.assessmentnotebook.core.PageLookup;
import com.assessmentnotebook.html.HtmlGenerator;
import com.assessmentnotebook.model.*;
import com.assessmentnotebook.repair.RepairReport.Category;
import com.assessmentnotebook.store.ProjectLayout;
import com.assessmentnotebook.store.ProjectStore;
import com.assessmentnotebook.store.Timestamps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks a project directory for inconsistencies and repairs what can be
 * repaired without guessing: records that point at things that no longer
 * exist, children their page has lost track of, links and redirects that were
 * never connected, resources recorded twice or under the wrong type, evidence
 * files that moved, and generated documents that no longer match the model.
 *
 * <p>The model ({@code project.json}) is the source of truth, so the repair
 * works on the model and then rebuilds every document from it. Three rules
 * keep it safe to run on a project someone cares about:
 * <ul>
 *   <li>the default is a <b>dry run</b>: everything is analysed and reported,
 *       nothing is written;</li>
 *   <li>when applying, {@code project.json} is copied aside before it is
 *       rewritten;</li>
 *   <li>nothing the tester wrote is deleted. A record that cannot be
 *       reconnected is reported, and removed only with {@link Options#prune},
 *       which also moves (never deletes) unreferenced evidence files.</li>
 * </ul>
 * It has no dependency on Burp and runs on a project that is not open.
 */
public final class ProjectRepair {

    public static final class Options {
        /** Write the fixes. Off = dry run. */
        public boolean apply = false;
        /**
         * Also remove auto-discovered records nothing can reach (forms,
         * parameters, links, script findings) and move unreferenced evidence
         * files into {@code _orphaned/}.
         */
        public boolean prune = false;
        /**
         * Turn JSON/XHR pages into API endpoints, and fold a resource that
         * duplicates an endpoint into it. Off = leave kinds as the tester set them.
         */
        public boolean reclassify = true;
    }

    // Group titles, shared with the tests.
    static final String RECOVERED = "Project file recovered from an interrupted save";
    static final String DEFAULTS = "Missing values restored to their defaults";
    static final String COUNTERS = "Id counters behind the ids in use";
    static final String BAD_IDS = "Records with a missing or duplicate id";
    static final String DUP_EDGES = "Duplicate relationship edges removed";
    static final String POSSIBLE_DUP_PAGES = "Pages registered twice under equivalent URLs (review; never merged automatically)";
    static final String DANGLING_LIST = "List entries pointing at records that do not exist";
    static final String DANGLING_REF = "References to records that do not exist cleared";
    static final String DANGLING_EDGES = "Relationship edges to records that do not exist removed";
    static final String NOTES_RETARGETED = "Notes on records that no longer exist moved to the project";
    static final String ORPHANS = "Records that belong to nothing (--prune removes those without your notes or tests)";
    static final String PRUNED = "Unreachable records removed";
    static final String REATTACHED = "Records re-attached to their page";
    static final String PARAMS_REATTACHED = "Parameters re-attached to their form";
    static final String LOADERS = "Page and resource loader lists brought into agreement";
    static final String MEMBERSHIP_EDGES = "Membership relationships restored";
    static final String LINKS = "Links connected to the page they lead to";
    static final String REDIRECTS = "Redirects connected to their destination page";
    static final String CALLERS = "API endpoints connected to the page that calls them";
    static final String FINDINGS = "Findings connected to the page they affect";
    static final String TESTS = "Parameter tests connected to their parameter";
    static final String REQUESTS = "Captured requests connected to their page and response";
    static final String RES_URL = "Resource URLs normalized";
    static final String RES_MERGED = "Duplicate resources merged";
    static final String RES_TYPE = "Resource types corrected from the file extension";
    static final String PAGE_KIND = "JSON/XHR pages reclassified as API endpoints";
    static final String RES_FOLDED = "Resources that duplicated an API endpoint folded into it";
    static final String RES_NO_URL = "Resources without a URL";
    static final String FIELDS = "Response fields filled in from saved bodies";
    static final String PATHS = "File paths normalized";
    static final String RELINKED = "Moved files found again by name";
    static final String MISSING_FILES = "References to files that no longer exist removed";
    static final String MISSING_IMAGES = "Screenshots whose image file is missing (--prune removes the record)";
    static final String ORPHAN_FILES = "Evidence files no record refers to (--prune moves them to _orphaned/)";
    static final String QUARANTINED = "Unreferenced evidence files moved to _orphaned/";
    static final String LEFTOVER_TMP = "Leftover from an interrupted save set aside";
    static final String STALE_DOCS = "Stale generated documents removed";
    static final String MISSING_DOCS = "Missing documents regenerated";

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String ORPHAN_DIR = "_orphaned";

    @FunctionalInterface
    private interface FileOp { void run() throws IOException; }

    private static final class Bucket {
        final EntityType type;
        final List<?> list;
        Bucket(EntityType type, List<?> list) { this.type = type; this.list = list; }
    }

    private final ProjectLayout layout;
    private final ProjectStore store;
    private final Options options;
    private final RepairReport report = new RepairReport();
    /** File-system changes decided during analysis; performed only when applying. */
    private final List<FileOp> fileOps = new ArrayList<>();

    private Project project;
    /** The model as it was read, to tell afterwards whether anything in it changed. */
    private String loadedJson;
    private boolean recoveredFromTmp;
    private Path leftoverTmp;

    private Map<EntityType, Set<String>> ids;
    private Map<String, Page> pages;
    private Set<Relationship> edgeSet;

    public ProjectRepair(Path root, Options options) {
        this.store = new ProjectStore(root);
        this.layout = store.layout();
        this.options = options == null ? new Options() : options;
    }

    /** Analyse the project and, if {@link Options#apply} is set, repair it. */
    public RepairReport run() throws IOException {
        report.root = layout.root;
        if (!load()) return report;

        restoreDefaults();
        report.projectSummary = summary();
        fixIds();

        canonicalizeResources();
        mergeDuplicateResources();
        fixResourceTypes();
        if (options.reclassify) {
            reclassifyPages();
            foldEndpointResources();
        }

        cleanLists();
        fixPageOwnership();
        fixParameterOwnership();
        syncResourceLoaders();
        clearDanglingReferences();
        cleanRelationships();

        ensureMembershipEdges();
        linkRequests();
        resolveLinks();
        detectRedirects();
        linkEndpointCallers();
        linkFindings();
        linkParameterTests();

        checkEvidenceFiles();
        backfillResponseFields();
        reportPossibleDuplicates();
        findOrphanFiles();
        findStaleDocuments();

        if (options.apply) apply(); else checkLinks();
        return report;
    }

    // ================================================================ loading

    private boolean load() {
        Path json = layout.projectJson();
        Path tmp = json.resolveSibling("project.json.tmp");
        if (!Files.isDirectory(layout.root)) {
            report.fatal = "Not a directory: " + layout.root;
            return false;
        }
        Project fromJson = parse(json);
        if (fromJson != null) {
            project = fromJson;
            if (Files.isRegularFile(tmp)) {
                // A save died after writing the temp file. project.json is the last
                // state known to be complete, so it stays the truth; the leftover is
                // kept under another name in case it holds something newer.
                leftoverTmp = tmp;
                report.fixed(Category.FILES, LEFTOVER_TMP,
                        "project.json.tmp (kept as project.json.interrupted-<time>)");
            }
        } else {
            Project fromTmp = parse(tmp);
            if (fromTmp == null) {
                report.fatal = Files.exists(json)
                        ? "project.json cannot be read as a project and there is no usable "
                          + "project.json.tmp to recover from. Restore it from a backup "
                          + "(project.json.bak-*) and run the check again."
                        : "No project.json in " + layout.root + " (is this a project directory?)";
                return false;
            }
            project = fromTmp;
            recoveredFromTmp = true;
            report.fixed(Category.STRUCTURE, RECOVERED, "project.json was "
                    + (Files.exists(json) ? "unreadable" : "missing")
                    + "; the model was read from project.json.tmp instead");
        }
        loadedJson = GSON.toJson(project);
        return true;
    }

    private static Project parse(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Project.class);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private String summary() {
        long endpoints = project.pages.stream().filter(p -> p.kind == Page.Kind.API).count();
        return "Project \"" + project.name + "\" — pages " + (project.pages.size() - endpoints)
                + ", API endpoints " + endpoints + ", forms " + project.forms.size()
                + ", parameters " + project.parameters.size() + ", resources "
                + project.resources.size() + ", links " + project.links.size() + ", variants "
                + project.variants.size() + ", findings " + project.vulnerabilities.size()
                + ", notes " + project.notes.size();
    }

    // ============================================================ structure

    /**
     * A field that is null in the file but has a value in a freshly constructed
     * record (an empty list, an empty string, a default enum constant) is put
     * back to that value. This is what hand-editing, an older version or an
     * unknown enum name leaves behind, and what the rest of the tool trips over.
     * Fields that are null by design (optional references) are left alone.
     */
    private void restoreDefaults() {
        Map<String, Integer> restored = new LinkedHashMap<>();
        restoreDefaults(project, restored, new IdentityHashMap<>());
        for (Map.Entry<String, Integer> e : restored.entrySet()) {
            report.fixed(Category.STRUCTURE, DEFAULTS, e.getKey() + " (" + e.getValue() + "x)");
        }
    }

    private void restoreDefaults(Object o, Map<String, Integer> restored, Map<Object, Boolean> seen) {
        if (o == null || !isModel(o.getClass()) || seen.put(o, Boolean.TRUE) != null) return;
        for (Field f : o.getClass().getFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) continue;
            try {
                Object value = f.get(o);
                if (value == null) {
                    Object fresh = newInstance(o.getClass());
                    Object def = fresh == null ? null : f.get(fresh);
                    if (def == null) continue; // optional by design
                    f.set(o, def);
                    value = def;
                    restored.merge(o.getClass().getSimpleName() + "." + f.getName(), 1, Integer::sum);
                }
                if (value instanceof List) {
                    List<?> list = (List<?>) value;
                    if (list.removeIf(java.util.Objects::isNull)) {
                        restored.merge(o.getClass().getSimpleName() + "." + f.getName()
                                + " (empty entries dropped)", 1, Integer::sum);
                    }
                    for (Object item : list) restoreDefaults(item, restored, seen);
                } else if (value instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) value;
                    if (map.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null)) {
                        restored.merge(o.getClass().getSimpleName() + "." + f.getName()
                                + " (empty entries dropped)", 1, Integer::sum);
                    }
                    for (Object item : map.values()) restoreDefaults(item, restored, seen);
                } else {
                    restoreDefaults(value, restored, seen);
                }
            } catch (IllegalAccessException | RuntimeException skip) {
                // An unmodifiable or inaccessible field is not ours to restore.
            }
        }
    }

    private static boolean isModel(Class<?> c) {
        return !c.isEnum() && c.getName().startsWith("com.assessmentnotebook.model.");
    }

    private static Object newInstance(Class<?> c) {
        try {
            return c.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException noDefaultConstructor) {
            return null;
        }
    }

    private List<Bucket> buckets() {
        return List.of(
                new Bucket(EntityType.TECHNOLOGY, project.technologies),
                new Bucket(EntityType.PAGE, project.pages),
                new Bucket(EntityType.RESOURCE, project.resources),
                new Bucket(EntityType.LINK, project.links),
                new Bucket(EntityType.FORM, project.forms),
                new Bucket(EntityType.PARAMETER, project.parameters),
                new Bucket(EntityType.INTERACTION, project.interactions),
                new Bucket(EntityType.REQUEST, project.requests),
                new Bucket(EntityType.RESPONSE, project.responses),
                new Bucket(EntityType.SCREENSHOT, project.screenshots),
                new Bucket(EntityType.NOTE, project.notes),
                new Bucket(EntityType.VULNERABILITY, project.vulnerabilities),
                new Bucket(EntityType.INTERESTING_STRING, project.interestingStrings),
                new Bucket(EntityType.PAGE_VARIANT, project.variants),
                new Bucket(EntityType.PARAM_TEST, project.parameterTests),
                new Bucket(EntityType.JS_FINDING, project.jsFindings),
                new Bucket(EntityType.ANNOTATION, annotations()));
    }

    private List<Annotation> annotations() {
        List<Annotation> all = new ArrayList<>();
        for (Screenshot s : project.screenshots) all.addAll(s.annotations);
        return all;
    }

    /**
     * Every record needs a unique id, and the per-type counter must be at least
     * the highest number in use, or the next thing registered is handed an id
     * that already exists.
     */
    private void fixIds() {
        for (Bucket b : buckets()) {
            int highest = 0;
            for (Object e : b.list) highest = Math.max(highest, suffix(b.type, idOf(e)));
            Integer stored = project.idCounters.get(b.type.slug);
            int counter = stored == null ? 0 : stored;
            if (counter < highest) {
                project.idCounters.put(b.type.slug, highest);
                report.fixed(Category.STRUCTURE, COUNTERS, b.type.slug + ": counter was " + counter
                        + " but " + String.format("%s-%04d", b.type.slug, highest) + " exists");
            }
        }
        for (Bucket b : buckets()) {
            Set<String> seen = new HashSet<>();
            for (Object e : b.list) {
                String id = idOf(e);
                if (id == null || id.isBlank()) {
                    String minted = project.nextId(b.type);
                    setId(e, minted);
                    seen.add(minted);
                    report.fixed(Category.STRUCTURE, BAD_IDS,
                            "a " + b.type.slug + " record had no id; it is now " + minted);
                } else if (!seen.add(id)) {
                    String minted = project.nextId(b.type);
                    setId(e, minted);
                    seen.add(minted);
                    report.fixed(Category.STRUCTURE, BAD_IDS, "two records shared the id " + id
                            + "; the second is now " + minted + " (references stay with the first)");
                }
            }
        }
        reindex();
    }

    /** The number in {@code page-0007}; 0 when the id is not in that form. */
    private static int suffix(EntityType type, String id) {
        String prefix = type.slug + "-";
        if (id == null || !id.startsWith(prefix)) return 0;
        try {
            return Integer.parseInt(id.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String idOf(Object e) {
        try {
            return (String) e.getClass().getField("id").get(e);
        } catch (ReflectiveOperationException | RuntimeException noId) {
            return null;
        }
    }

    private static void setId(Object e, String id) {
        try {
            e.getClass().getField("id").set(e, id);
        } catch (ReflectiveOperationException | RuntimeException noId) {
            // A record type without an id field has nothing to set.
        }
    }

    /** Rebuild the id and page lookups after records were added, removed or renamed. */
    private void reindex() {
        ids = new HashMap<>();
        for (Bucket b : buckets()) {
            Set<String> set = new HashSet<>();
            for (Object e : b.list) set.add(idOf(e));
            ids.put(b.type, set);
        }
        pages = new LinkedHashMap<>();
        for (Page p : project.pages) pages.putIfAbsent(p.id, p);
    }

    private boolean exists(EntityType type, String id) {
        if (type == null) return false;
        if (type == EntityType.PROJECT) return true;
        Set<String> set = ids.get(type);
        return id != null && set != null && set.contains(id);
    }

    // ============================================================= resources

    /** A resource is identified by its canonical URL; store it that way. */
    private void canonicalizeResources() {
        for (Resource r : project.resources) {
            if (r.url.isBlank()) {
                report.attention(Category.RESOURCES, RES_NO_URL, r.id);
                continue;
            }
            String canonical = ResourceUrls.canonical(r.url);
            if (canonical.equals(r.url)) continue;
            if (!r.observedUrls.contains(r.url)) r.observedUrls.add(r.url);
            report.fixed(Category.RESOURCES, RES_URL, r.id + ": " + r.url + " → " + canonical);
            r.url = canonical;
        }
    }

    /** The same file recorded more than once becomes one record with every loader. */
    private void mergeDuplicateResources() {
        Map<String, Resource> byUrl = new HashMap<>();
        Map<String, String> renamed = new HashMap<>();
        for (Iterator<Resource> it = project.resources.iterator(); it.hasNext(); ) {
            Resource r = it.next();
            if (r.url.isBlank()) continue;
            Resource first = byUrl.putIfAbsent(r.url, r);
            if (first == null) continue;
            for (String pid : r.pageIds) if (!first.pageIds.contains(pid)) first.pageIds.add(pid);
            for (String u : r.observedUrls) if (!first.observedUrls.contains(u)) first.observedUrls.add(u);
            if (Resource.specificity(r.type) > Resource.specificity(first.type)) first.type = r.type;
            if (first.sourceFile == null || first.sourceFile.isBlank()) first.sourceFile = r.sourceFile;
            first.notes = joinNotes(first.notes, r.notes);
            first.createdAt = earlier(first.createdAt, r.createdAt);
            renamed.put(r.id, first.id);
            it.remove();
            report.fixed(Category.RESOURCES, RES_MERGED, r.id + " was a second record of "
                    + first.id + " (" + first.url + ")");
        }
        if (renamed.isEmpty()) return;

        for (Page p : project.pages) replaceIds(p.resourceIds, renamed);
        for (JsFinding f : project.jsFindings) f.resourceId = renamed.getOrDefault(f.resourceId, f.resourceId);
        for (Note n : project.notes) {
            if (n.targetType == EntityType.RESOURCE) n.targetId = renamed.getOrDefault(n.targetId, n.targetId);
        }
        for (Relationship e : project.relationships) {
            if (e.fromType == EntityType.RESOURCE) e.fromId = renamed.getOrDefault(e.fromId, e.fromId);
            if (e.toType == EntityType.RESOURCE) e.toId = renamed.getOrDefault(e.toId, e.toId);
        }
        edgeSet = null;
        // The merged records may have carried the same script findings.
        Set<String> seen = new HashSet<>();
        project.jsFindings.removeIf(f -> !seen.add(f.resourceId + "|" + f.kind + "|" + f.value));
        reindex();
    }

    private static void replaceIds(List<String> list, Map<String, String> renamed) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : list) out.add(renamed.getOrDefault(id, id));
        if (!new ArrayList<>(out).equals(list)) {
            list.clear();
            list.addAll(out);
        }
    }

    /**
     * A static file's extension says what it is. A generic or contradicting
     * type is corrected; API/XHR classifications are the tester's and stay.
     */
    private void fixResourceTypes() {
        for (Resource r : project.resources) {
            Resource.Type byExtension = typeByExtension(r.url);
            if (byExtension == null || byExtension == r.type) continue;
            boolean replaceable = r.type == Resource.Type.OTHER || r.type == Resource.Type.SCRIPT
                    || r.type == Resource.Type.STYLESHEET || r.type == Resource.Type.IMAGE
                    || r.type == Resource.Type.FONT;
            if (!replaceable) continue;
            report.fixed(Category.RESOURCES, RES_TYPE, r.id + " (" + leaf(r.url) + "): "
                    + r.type.label + " → " + byExtension.label);
            r.type = byExtension;
        }
    }

    private static Resource.Type typeByExtension(String url) {
        String path = UrlTemplates.path(url).toLowerCase(Locale.ROOT);
        int dot = path.lastIndexOf('.');
        if (dot < path.lastIndexOf('/') || dot < 0) return null;
        switch (path.substring(dot + 1)) {
            case "js": case "mjs": case "cjs":
                return Resource.Type.SCRIPT;
            case "css":
                return Resource.Type.STYLESHEET;
            case "png": case "jpg": case "jpeg": case "gif": case "svg":
            case "webp": case "ico": case "bmp": case "avif":
                return Resource.Type.IMAGE;
            case "woff": case "woff2": case "ttf": case "otf": case "eot":
                return Resource.Type.FONT;
            default:
                return null;
        }
    }

    /**
     * A page whose response is data (JSON, XML) or whose request was an XHR is
     * an API endpoint. Projects made before endpoints were a kind of their own
     * have them all filed as pages.
     */
    private void reclassifyPages() {
        Map<String, List<RequestRecord>> requests = requestsByPage();
        for (Page p : project.pages) {
            if (p.kind != Page.Kind.PAGE) continue;
            List<RequestRecord> own = requests.getOrDefault(p.id, List.of());
            List<String> headers = own.isEmpty() ? null : own.get(0).headers;
            // Classified as a GET on purpose: the classifier's fallback of "a
            // non-GET with no other signal is an API call" is a guess, and a
            // guess is not enough to re-file a record the tester registered.
            TrafficClassifier.Kind kind = TrafficClassifier.classify("GET", p.url, headers,
                    p.statusCode, p.contentType).kind;
            if (kind != TrafficClassifier.Kind.API) continue;
            p.kind = Page.Kind.API;
            report.fixed(Category.RESOURCES, PAGE_KIND, p.id + " " + p.method + " " + p.url
                    + (p.contentType.isBlank() ? "" : " (" + p.contentType + ")"));
        }
    }

    /**
     * An API call registered both as an endpoint and as a resource is one
     * thing. The resource is folded into the endpoint: the pages that "loaded"
     * it become the endpoint's callers, and its notes and saved body move over.
     */
    private void foldEndpointResources() {
        PageLookup lookup = new PageLookup(project);
        Set<String> scanned = new HashSet<>();
        for (JsFinding f : project.jsFindings) scanned.add(f.resourceId);
        List<Resource> folded = new ArrayList<>();
        for (Resource r : project.resources) {
            boolean data = r.type == Resource.Type.API || r.type == Resource.Type.XHR
                    || r.type == Resource.Type.OTHER;
            if (!data || r.url.isBlank() || typeByExtension(r.url) != null
                    || scanned.contains(r.id)) {
                continue;
            }
            // Only an endpoint absorbs its duplicate. An HTML page that another
            // page also loads (in a frame, say) is legitimately both.
            Page page = lookup.findSameUrl(r.url);
            if (page == null || page.kind != Page.Kind.API) continue;
            int callers = 0;
            for (String pid : r.pageIds) {
                Page loader = pages.get(pid);
                if (loader == null) continue;
                loader.resourceIds.remove(r.id);
                if (loader != page && addEdge(EntityType.PAGE, loader.id, Relationship.CALLS,
                        EntityType.PAGE, page.id, "")) {
                    callers++;
                }
            }
            for (Page p : project.pages) p.resourceIds.remove(r.id);
            page.notes = joinNotes(page.notes, r.notes);
            if (r.sourceFile != null && !r.sourceFile.isBlank()
                    && !page.sourceFiles.contains(r.sourceFile)) {
                page.sourceFiles.add(r.sourceFile);
            }
            for (Note n : project.notes) {
                if (n.targetType == EntityType.RESOURCE && r.id.equals(n.targetId)) {
                    n.targetType = EntityType.PAGE;
                    n.targetId = page.id;
                }
            }
            for (Iterator<Relationship> it = project.relationships.iterator(); it.hasNext(); ) {
                Relationship e = it.next();
                boolean from = e.fromType == EntityType.RESOURCE && r.id.equals(e.fromId);
                boolean to = e.toType == EntityType.RESOURCE && r.id.equals(e.toId);
                if (!from && !to) continue;
                if (Relationship.LOADS_RESOURCE.equals(e.kind)) {
                    it.remove(); // replaced by the calls edges above
                    continue;
                }
                if (from) { e.fromType = EntityType.PAGE; e.fromId = page.id; }
                if (to) { e.toType = EntityType.PAGE; e.toId = page.id; }
            }
            edgeSet = null;
            folded.add(r);
            report.fixed(Category.RESOURCES, RES_FOLDED, r.id + " → " + page.id + " (" + r.url + ")"
                    + (callers == 0 ? "" : ", " + callers + " loading page(s) now listed as callers"));
        }
        if (folded.isEmpty()) return;
        project.resources.removeAll(folded);
        reindex();
    }

    // ============================================================ references

    /** Lists of ids hold each existing id once. */
    private void cleanLists() {
        for (Page p : project.pages) {
            cleanList(p.id + " formIds", p.formIds, EntityType.FORM);
            cleanList(p.id + " linkIds", p.linkIds, EntityType.LINK);
            cleanList(p.id + " resourceIds", p.resourceIds, EntityType.RESOURCE);
            cleanList(p.id + " screenshotIds", p.screenshotIds, EntityType.SCREENSHOT);
            cleanList(p.id + " interactionIds", p.interactionIds, EntityType.INTERACTION);
            cleanList(p.id + " variantIds", p.variantIds, EntityType.PAGE_VARIANT);
        }
        for (Form f : project.forms) cleanList(f.id + " parameterIds", f.parameterIds, EntityType.PARAMETER);
        for (Resource r : project.resources) cleanList(r.id + " pageIds", r.pageIds, EntityType.PAGE);
        for (Vulnerability v : project.vulnerabilities) {
            cleanList(v.id + " screenshotIds", v.screenshotIds, EntityType.SCREENSHOT);
            cleanList(v.id + " requestIds", v.requestIds, EntityType.REQUEST);
        }
    }

    private void cleanList(String owner, List<String> list, EntityType type) {
        Set<String> seen = new HashSet<>();
        for (Iterator<String> it = list.iterator(); it.hasNext(); ) {
            String id = it.next();
            if (!exists(type, id)) {
                it.remove();
                report.fixed(Category.REFERENCES, DANGLING_LIST, owner + ": " + id);
            } else if (!seen.add(id)) {
                it.remove();
                report.fixed(Category.REFERENCES, DANGLING_LIST, owner + ": " + id + " (listed twice)");
            }
        }
    }

    /**
     * Forms, links, variants, screenshots and interactions each belong to one
     * page, recorded twice: on the child ({@code pageId}) and in the page's
     * list. When the two disagree the page's list wins (it is the documented
     * authority); when only one side survives, the other is rebuilt from it;
     * when neither does, a relationship edge or the URL may still say where the
     * child belongs.
     */
    private void fixPageOwnership() {
        Set<String> tested = new HashSet<>();
        for (ParameterTest t : project.parameterTests) if (t.parameterId != null) tested.add(t.parameterId);
        Map<String, Parameter> params = new HashMap<>();
        for (Parameter p : project.parameters) params.put(p.id, p);

        own("form", project.forms, f -> f.id, f -> f.pageId, (f, id) -> f.pageId = id,
                p -> p.formIds, Relationship.CONTAINS_FORM, EntityType.FORM, null,
                f -> f.notes.isBlank() && f.parameterIds.stream().allMatch(
                        pid -> disposable(params.get(pid), tested)));
        own("link", project.links, l -> l.id, l -> l.sourcePageId, (l, id) -> l.sourcePageId = id,
                p -> p.linkIds, Relationship.CONTAINS_LINK, EntityType.LINK, null,
                l -> l.notes.isBlank());
        own("variant", project.variants, v -> v.id, v -> v.pageId, (v, id) -> v.pageId = id,
                p -> p.variantIds, null, EntityType.PAGE_VARIANT, this::pageForVariant, v -> false);
        own("screenshot", project.screenshots, s -> s.id, s -> s.pageId, (s, id) -> s.pageId = id,
                p -> p.screenshotIds, null, EntityType.SCREENSHOT, null, s -> false);
        own("interaction", project.interactions, a -> a.id, a -> a.pageId, (a, id) -> a.pageId = id,
                p -> p.interactionIds, null, EntityType.INTERACTION, null, a -> false);
        reindex();
    }

    /** A parameter nobody described or tested: safe to drop when it is unreachable. */
    private static boolean disposable(Parameter p, Set<String> tested) {
        return p == null || (p.purpose.isBlank() && p.notes.isBlank() && p.reflections.isEmpty()
                && !tested.contains(p.id));
    }

    private <T> void own(String what, List<T> children, Function<T, String> idOf,
            Function<T, String> ownerOf, BiConsumer<T, String> setOwner,
            Function<Page, List<String>> listOf, String edgeKind, EntityType childType,
            Function<T, Page> fallback, Predicate<T> disposable) {
        Map<String, List<Page>> listers = new HashMap<>();
        for (Page p : project.pages) {
            for (String cid : listOf.apply(p)) {
                listers.computeIfAbsent(cid, k -> new ArrayList<>()).add(p);
            }
        }
        List<T> removed = new ArrayList<>();
        for (T child : children) {
            String cid = idOf.apply(child);
            String ownerId = ownerOf.apply(child);
            Page owner = ownerId == null ? null : pages.get(ownerId);
            List<Page> listedBy = listers.getOrDefault(cid, List.of());
            Page chosen;
            if (owner != null && listedBy.contains(owner)) {
                chosen = owner;
            } else if (!listedBy.isEmpty()) {
                chosen = listedBy.get(0);
                setOwner.accept(child, chosen.id);
                report.fixed(Category.CONNECTIONS, REATTACHED, what + " " + cid + " is listed by "
                        + chosen.id + " but pointed at " + describe(ownerId, owner));
            } else if (owner != null) {
                chosen = owner;
                listOf.apply(owner).add(cid);
                report.fixed(Category.CONNECTIONS, REATTACHED, what + " " + cid + " names "
                        + owner.id + " as its page, which had lost it from its list");
            } else {
                Page found = edgeKind == null ? null : pageWithEdgeTo(edgeKind, childType, cid);
                if (found == null && fallback != null) found = fallback.apply(child);
                if (found == null) {
                    if (options.prune && disposable.test(child)) {
                        removed.add(child);
                        report.fixed(Category.REFERENCES, PRUNED, what + " " + cid + " (no page)");
                    } else {
                        report.attention(Category.REFERENCES, ORPHANS, what + " " + cid
                                + " is not on any page" + (ownerId == null || ownerId.isBlank()
                                        ? "" : " (its page " + ownerId + " does not exist)"));
                    }
                    continue;
                }
                chosen = found;
                setOwner.accept(child, found.id);
                listOf.apply(found).add(cid);
                report.fixed(Category.CONNECTIONS, REATTACHED, what + " " + cid
                        + " had lost its page; re-attached to " + found.id);
            }
            for (Page other : listedBy) {
                if (other == chosen) continue;
                listOf.apply(other).remove(cid);
                report.fixed(Category.CONNECTIONS, REATTACHED, what + " " + cid + " was also listed by "
                        + other.id + "; it belongs to " + chosen.id);
            }
        }
        children.removeAll(removed);
    }

    private static String describe(String ownerId, Page owner) {
        if (ownerId == null || ownerId.isBlank()) return "no page";
        return owner == null ? ownerId + ", which does not exist" : ownerId;
    }

    private Page pageWithEdgeTo(String kind, EntityType toType, String toId) {
        for (Relationship e : project.relationships) {
            if (e.fromType == EntityType.PAGE && e.toType == toType && kind.equals(e.kind)
                    && toId.equals(e.toId) && pages.containsKey(e.fromId)) {
                return pages.get(e.fromId);
            }
        }
        return null;
    }

    /** Variants are filed by path and method, as registration does. */
    private Page pageForVariant(PageVariant v) {
        if (v.url.isBlank()) return null;
        String key = UrlTemplates.key(v.url, false);
        Page anyMethod = null;
        for (Page p : project.pages) {
            if (!UrlTemplates.key(p.url, false).equals(key)) continue;
            if (p.method.equalsIgnoreCase(v.method)) return p;
            if (anyMethod == null) anyMethod = p;
        }
        return anyMethod;
    }

    /** A parameter must be on at least one form to appear anywhere. */
    private void fixParameterOwnership() {
        Set<String> listed = new HashSet<>();
        Map<String, Form> forms = new HashMap<>();
        for (Form f : project.forms) {
            forms.put(f.id, f);
            listed.addAll(f.parameterIds);
        }
        Set<String> tested = new HashSet<>();
        for (ParameterTest t : project.parameterTests) if (t.parameterId != null) tested.add(t.parameterId);

        List<Parameter> removed = new ArrayList<>();
        for (Parameter p : project.parameters) {
            if (listed.contains(p.id)) continue;
            Form home = null;
            for (Relationship e : project.relationships) {
                if (e.fromType == EntityType.FORM && e.toType == EntityType.PARAMETER
                        && Relationship.HAS_PARAMETER.equals(e.kind) && p.id.equals(e.toId)
                        && forms.containsKey(e.fromId)) {
                    home = forms.get(e.fromId);
                    break;
                }
            }
            if (home != null) {
                home.parameterIds.add(p.id);
                report.fixed(Category.CONNECTIONS, PARAMS_REATTACHED, "parameter " + p.id + " ("
                        + p.name + ") → " + home.id);
            } else if (options.prune && disposable(p, tested)) {
                removed.add(p);
                report.fixed(Category.REFERENCES, PRUNED, "parameter " + p.id + " (" + p.name
                        + ", on no form)");
            } else {
                report.attention(Category.REFERENCES, ORPHANS, "parameter " + p.id + " (" + p.name
                        + ") is not on any form");
            }
        }
        project.parameters.removeAll(removed);
        reindex();
    }

    /** "Page loads resource" is recorded on both sides and as an edge; make all three agree. */
    private void syncResourceLoaders() {
        Map<String, Resource> resources = new HashMap<>();
        for (Resource r : project.resources) resources.put(r.id, r);
        for (Relationship e : project.relationships) {
            if (e.fromType != EntityType.PAGE || e.toType != EntityType.RESOURCE
                    || !Relationship.LOADS_RESOURCE.equals(e.kind)) {
                continue;
            }
            Page p = pages.get(e.fromId);
            Resource r = resources.get(e.toId);
            if (p != null && r != null && !p.resourceIds.contains(r.id)
                    && !r.pageIds.contains(p.id)) {
                p.resourceIds.add(r.id);
                report.fixed(Category.CONNECTIONS, LOADERS, p.id + " loads " + r.id
                        + " (known only from a relationship edge)");
            }
        }
        for (Page p : project.pages) {
            for (String rid : p.resourceIds) {
                Resource r = resources.get(rid);
                if (r != null && !r.pageIds.contains(p.id)) {
                    r.pageIds.add(p.id);
                    report.fixed(Category.CONNECTIONS, LOADERS, r.id + " did not list " + p.id
                            + " as a page that loads it");
                }
            }
        }
        for (Resource r : project.resources) {
            for (String pid : r.pageIds) {
                Page p = pages.get(pid);
                if (p != null && !p.resourceIds.contains(r.id)) {
                    p.resourceIds.add(r.id);
                    report.fixed(Category.CONNECTIONS, LOADERS, p.id + " did not list " + r.id
                            + " among its resources");
                }
            }
        }
    }

    /** Optional single references to something that is gone are emptied. */
    private void clearDanglingReferences() {
        for (Link l : project.links) {
            if (gone(EntityType.PAGE, l.destinationPageId)) {
                cleared("link " + l.id + " destinationPageId", l.destinationPageId);
                l.destinationPageId = null;
            }
        }
        for (Interaction a : project.interactions) {
            if (gone(EntityType.REQUEST, a.requestId)) {
                cleared("interaction " + a.id + " requestId", a.requestId);
                a.requestId = null;
            }
            if (gone(EntityType.SCREENSHOT, a.screenshotId)) {
                cleared("interaction " + a.id + " screenshotId", a.screenshotId);
                a.screenshotId = null;
            }
        }
        for (Screenshot s : project.screenshots) {
            if (gone(EntityType.INTERACTION, s.relatedInteractionId)) {
                cleared("screenshot " + s.id + " relatedInteractionId", s.relatedInteractionId);
                s.relatedInteractionId = null;
            }
            if (gone(EntityType.REQUEST, s.relatedRequestId)) {
                cleared("screenshot " + s.id + " relatedRequestId", s.relatedRequestId);
                s.relatedRequestId = null;
            }
            for (Annotation a : s.annotations) {
                if (gone(EntityType.REQUEST, a.relatedRequestId)) {
                    cleared("annotation " + a.id + " relatedRequestId", a.relatedRequestId);
                    a.relatedRequestId = null;
                }
            }
        }
        for (RequestRecord r : project.requests) {
            if (gone(EntityType.RESPONSE, r.responseId)) {
                cleared("request " + r.id + " responseId", r.responseId);
                r.responseId = null;
            }
        }
        for (ParameterTest t : project.parameterTests) {
            if (gone(EntityType.PAGE, t.pageId)) { cleared("test " + t.id + " pageId", t.pageId); t.pageId = null; }
            if (gone(EntityType.FORM, t.formId)) { cleared("test " + t.id + " formId", t.formId); t.formId = null; }
            if (gone(EntityType.PARAMETER, t.parameterId)) {
                cleared("test " + t.id + " parameterId", t.parameterId);
                t.parameterId = null;
            }
            if (gone(EntityType.PAGE_VARIANT, t.variantId)) {
                cleared("test " + t.id + " variantId", t.variantId);
                t.variantId = null;
            }
            if (gone(EntityType.REQUEST, t.requestId)) {
                cleared("test " + t.id + " requestId", t.requestId);
                t.requestId = null;
            }
            if (gone(EntityType.VULNERABILITY, t.promotedVulnId)) {
                cleared("test " + t.id + " promotedVulnId", t.promotedVulnId);
                t.promotedVulnId = null;
            }
        }
        for (InterestingString s : project.interestingStrings) {
            if (gone(EntityType.PAGE, s.sourcePageId)) {
                cleared("interesting string " + s.id + " sourcePageId", s.sourcePageId);
                s.sourcePageId = null;
            }
        }
        for (Page p : project.pages) {
            if (p.baseline != null && p.baseline.pageId != null && !p.id.equals(p.baseline.pageId)) {
                p.baseline.pageId = null; // a held baseline belongs to the page holding it
            }
        }

        // A note is the tester's writing: when its subject is gone it is kept,
        // at project level, saying what it used to be about.
        for (Note n : project.notes) {
            if (n.targetType == EntityType.PROJECT || exists(n.targetType, n.targetId)) continue;
            report.fixed(Category.REFERENCES, NOTES_RETARGETED, n.id + " (was on "
                    + n.targetType.slug + " " + n.targetId + ")");
            n.text = "[was attached to " + n.targetType.slug + " " + n.targetId
                    + ", which no longer exists] " + n.text;
            n.targetType = EntityType.PROJECT;
            n.targetId = "";
        }

        List<JsFinding> removed = new ArrayList<>();
        for (JsFinding f : project.jsFindings) {
            if (exists(EntityType.RESOURCE, f.resourceId)) continue;
            if (options.prune) {
                removed.add(f);
                report.fixed(Category.REFERENCES, PRUNED, "script finding " + f.id + " (its script "
                        + f.resourceId + " does not exist)");
            } else {
                report.attention(Category.REFERENCES, ORPHANS, "script finding " + f.id + " \""
                        + shorten(f.value) + "\": its script " + f.resourceId + " does not exist");
            }
        }
        if (project.jsFindings.removeAll(removed)) reindex();
    }

    private boolean gone(EntityType type, String id) {
        return id != null && !id.isBlank() && !exists(type, id);
    }

    private void cleared(String what, String id) {
        report.fixed(Category.REFERENCES, DANGLING_REF, what + " = " + id);
    }

    /** Edges must join two existing records, and each edge is stored once. */
    private void cleanRelationships() {
        Set<Relationship> seen = new HashSet<>();
        for (Iterator<Relationship> it = project.relationships.iterator(); it.hasNext(); ) {
            Relationship e = it.next();
            if (e.fromType == null || e.toType == null || e.kind == null
                    || !exists(e.fromType, e.fromId) || !exists(e.toType, e.toId)) {
                it.remove();
                report.fixed(Category.REFERENCES, DANGLING_EDGES, edge(e));
            } else if (!seen.add(e)) {
                it.remove();
                report.fixed(Category.STRUCTURE, DUP_EDGES, edge(e));
            }
        }
        edgeSet = seen;
    }

    private static String edge(Relationship e) {
        return (e.fromType == null ? "?" : e.fromType.slug) + " " + e.fromId + " —" + e.kind + "→ "
                + (e.toType == null ? "?" : e.toType.slug) + " " + e.toId;
    }

    // =========================================================== connections

    private Set<Relationship> edges() {
        if (edgeSet == null) edgeSet = new HashSet<>(project.relationships);
        return edgeSet;
    }

    /** Add an edge unless an equal one exists; true when it was added. */
    private boolean addEdge(EntityType fromType, String fromId, String kind, EntityType toType,
            String toId, String detail) {
        Relationship r = new Relationship(fromType, fromId, kind, toType, toId);
        r.detail = detail == null ? "" : detail;
        if (!edges().add(r)) return false;
        r.createdAt = Timestamps.now();
        project.relationships.add(r);
        return true;
    }

    /** The edges registration writes alongside each membership list. */
    private void ensureMembershipEdges() {
        for (Page p : project.pages) {
            for (String id : p.formIds) membership(EntityType.PAGE, p.id, Relationship.CONTAINS_FORM, EntityType.FORM, id);
            for (String id : p.linkIds) membership(EntityType.PAGE, p.id, Relationship.CONTAINS_LINK, EntityType.LINK, id);
            for (String id : p.resourceIds) membership(EntityType.PAGE, p.id, Relationship.LOADS_RESOURCE, EntityType.RESOURCE, id);
        }
        for (Form f : project.forms) {
            for (String id : f.parameterIds) membership(EntityType.FORM, f.id, Relationship.HAS_PARAMETER, EntityType.PARAMETER, id);
        }
    }

    private void membership(EntityType fromType, String fromId, String kind, EntityType toType, String toId) {
        if (addEdge(fromType, fromId, kind, toType, toId, "")) {
            report.fixed(Category.CONNECTIONS, MEMBERSHIP_EDGES, fromId + " " + kind + " " + toId);
        }
    }

    private Map<String, List<RequestRecord>> requestsByPage() {
        Map<String, RequestRecord> byId = new HashMap<>();
        for (RequestRecord r : project.requests) byId.put(r.id, r);
        Map<String, List<RequestRecord>> out = new HashMap<>();
        for (Relationship e : project.relationships) {
            if (e.fromType != EntityType.PAGE || e.toType != EntityType.REQUEST) continue;
            RequestRecord r = byId.get(e.toId);
            if (r != null) out.computeIfAbsent(e.fromId, k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    /** Requests belong to the page they fetched and name the response they produced. */
    private void linkRequests() {
        Set<String> owned = new HashSet<>();
        for (List<RequestRecord> list : requestsByPage().values()) {
            for (RequestRecord r : list) owned.add(r.id);
        }
        for (RequestRecord r : project.requests) {
            if (!owned.contains(r.id)) {
                Page page = project.findPageByRequest(r.url, r.method);
                if (page != null && addEdge(EntityType.PAGE, page.id, Relationship.RELATES_TO,
                        EntityType.REQUEST, r.id, "request that fetched this page")) {
                    report.fixed(Category.CONNECTIONS, REQUESTS, r.id + " → " + page.id + " (same URL)");
                }
            }
            if (r.responseId != null) {
                if (addEdge(EntityType.REQUEST, r.id, Relationship.PRODUCES_RESPONSE,
                        EntityType.RESPONSE, r.responseId, "")) {
                    report.fixed(Category.CONNECTIONS, REQUESTS, r.id + " produces " + r.responseId);
                }
                continue;
            }
            for (Relationship e : project.relationships) {
                if (e.fromType == EntityType.REQUEST && r.id.equals(e.fromId)
                        && e.toType == EntityType.RESPONSE
                        && Relationship.PRODUCES_RESPONSE.equals(e.kind)) {
                    r.responseId = e.toId;
                    report.fixed(Category.CONNECTIONS, REQUESTS, r.id + " produces " + e.toId
                            + " (known only from a relationship edge)");
                    break;
                }
            }
        }
    }

    /** A link whose destination is a registered page points at that page's document. */
    private void resolveLinks() {
        PageLookup lookup = new PageLookup(project);
        for (Link l : project.links) {
            if (l.destinationPageId != null) {
                // Resolved earlier but the links-to edge was lost.
                if (l.sourcePageId != null && addEdge(EntityType.PAGE, l.sourcePageId,
                        Relationship.LINKS_TO, EntityType.PAGE, l.destinationPageId, "")) {
                    report.fixed(Category.CONNECTIONS, LINKS, l.sourcePageId + " links to "
                            + l.destinationPageId + " (edge restored)");
                }
                continue;
            }
            Page target = lookup.find(l.destinationUrl);
            if (target == null) continue;
            l.destinationPageId = target.id;
            if (l.sourcePageId != null) {
                addEdge(EntityType.PAGE, l.sourcePageId, Relationship.LINKS_TO,
                        EntityType.PAGE, target.id, "");
            }
            report.fixed(Category.CONNECTIONS, LINKS, "link " + l.id + " on " + l.sourcePageId
                    + " → " + target.id + " (" + l.destinationUrl + ")");
        }
    }

    /** A captured 3xx whose Location is a registered page is how that page was reached. */
    private void detectRedirects() {
        PageLookup lookup = new PageLookup(project);
        Map<String, RequestRecord> byResponse = new HashMap<>();
        for (RequestRecord r : project.requests) if (r.responseId != null) byResponse.put(r.responseId, r);
        Map<String, Page> ownerOfRequest = new HashMap<>();
        for (Map.Entry<String, List<RequestRecord>> e : requestsByPage().entrySet()) {
            Page owner = pages.get(e.getKey());
            if (owner != null) for (RequestRecord r : e.getValue()) ownerOfRequest.putIfAbsent(r.id, owner);
        }
        for (ResponseRecord resp : project.responses) {
            if (resp.statusCode < 300 || resp.statusCode >= 400) continue;
            String location = header(resp.headers, "location");
            RequestRecord request = byResponse.get(resp.id);
            Page source = request == null ? null : ownerOfRequest.get(request.id);
            if (location.isEmpty() || source == null) continue;
            Page target = lookup.find(resolve(request.url.isBlank() ? source.url : request.url, location));
            if (target == null || target == source) continue;
            if (!addEdge(EntityType.PAGE, source.id, Relationship.REDIRECTS_TO, EntityType.PAGE,
                    target.id, "HTTP " + resp.statusCode + " redirect")) {
                continue;
            }
            if (target.discoverySourceKind == DiscoverySource.MANUAL
                    || target.discoverySourceKind == DiscoverySource.DIRECT_NAVIGATION) {
                target.discoverySourceKind = DiscoverySource.HTTP_REDIRECT;
                if (target.discoverySource.isBlank()) target.discoverySource = "redirected from " + source.url;
            }
            report.fixed(Category.CONNECTIONS, REDIRECTS, source.id + " —HTTP " + resp.statusCode
                    + "→ " + target.id + " (" + target.url + ")");
        }
    }

    /** The Referer of an endpoint's captured request names the page whose scripts call it. */
    private void linkEndpointCallers() {
        PageLookup lookup = new PageLookup(project);
        Map<String, List<RequestRecord>> requests = requestsByPage();
        for (Page endpoint : project.pages) {
            if (endpoint.kind != Page.Kind.API) continue;
            for (RequestRecord r : requests.getOrDefault(endpoint.id, List.of())) {
                Page caller = lookup.find(header(r.headers, "referer"));
                if (caller == null || caller == endpoint || caller.kind != Page.Kind.PAGE) continue;
                if (addEdge(EntityType.PAGE, caller.id, Relationship.CALLS, EntityType.PAGE,
                        endpoint.id, "")) {
                    report.fixed(Category.CONNECTIONS, CALLERS, caller.id + " calls " + endpoint.id
                            + " (" + endpoint.method + " " + endpoint.url + ")");
                }
            }
        }
    }

    /** A finding raised before its page was registered never got linked to it. */
    private void linkFindings() {
        PageLookup lookup = new PageLookup(project);
        Set<String> linked = new HashSet<>();
        for (Relationship e : project.relationships) {
            if (e.fromType == EntityType.VULNERABILITY && e.toType == EntityType.PAGE
                    && Relationship.AFFECTS.equals(e.kind)) {
                linked.add(e.fromId);
            }
        }
        for (Vulnerability v : project.vulnerabilities) {
            if (linked.contains(v.id)) continue;
            Page page = lookup.find(v.affectedUrl);
            if (page != null && addEdge(EntityType.VULNERABILITY, v.id, Relationship.AFFECTS,
                    EntityType.PAGE, page.id, "")) {
                report.fixed(Category.CONNECTIONS, FINDINGS, v.id + " \"" + shorten(v.title)
                        + "\" affects " + page.id);
            }
        }
    }

    /** A probe result names its parameter; link it when the page has exactly one by that name. */
    private void linkParameterTests() {
        Map<String, Form> forms = new HashMap<>();
        for (Form f : project.forms) forms.put(f.id, f);
        Map<String, Parameter> params = new HashMap<>();
        for (Parameter p : project.parameters) params.put(p.id, p);
        for (ParameterTest t : project.parameterTests) {
            if (t.parameterId != null || t.pageId == null || t.parameterName.isBlank()) continue;
            Page page = pages.get(t.pageId);
            if (page == null) continue;
            String found = null;
            boolean ambiguous = false;
            for (String fid : page.formIds) {
                Form f = forms.get(fid);
                if (f == null) continue;
                for (String pid : f.parameterIds) {
                    Parameter p = params.get(pid);
                    if (p == null || !t.parameterName.equals(p.name)) continue;
                    if (found != null && !found.equals(pid)) ambiguous = true;
                    found = pid;
                }
            }
            if (found == null || ambiguous) continue;
            t.parameterId = found;
            addEdge(EntityType.PARAMETER, found, Relationship.RELATES_TO, EntityType.PARAM_TEST,
                    t.id, t.probeLabel);
            report.fixed(Category.CONNECTIONS, TESTS, t.id + " (" + t.parameterName + ") → " + found);
        }
    }

    // ================================================================= files

    /**
     * Every file a record names must exist inside the project. A reference
     * written on another machine (absolute, or with backslashes) is rewritten
     * relative; a file that is not where the record says but sits in the
     * expected folder under the same name is linked again; a reference to a
     * file that is really gone is dropped so documents stop pointing at it.
     */
    private void checkEvidenceFiles() {
        for (Page p : project.pages) {
            for (int i = 0; i < p.sourceFiles.size(); i++) {
                String found = locate(p.id, p.sourceFiles.get(i), layout.source());
                if (found == null) {
                    missing(p.id, p.sourceFiles.remove(i--));
                } else {
                    p.sourceFiles.set(i, found);
                }
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>(p.sourceFiles);
            if (unique.size() != p.sourceFiles.size()) {
                p.sourceFiles.clear();
                p.sourceFiles.addAll(unique);
            }
            if (p.baseline != null && present(p.baseline.sourceFile)) {
                p.baseline.sourceFile = located(p.id + " baseline", p.baseline.sourceFile, layout.source());
            }
        }
        for (Resource r : project.resources) {
            if (present(r.sourceFile)) r.sourceFile = located(r.id, r.sourceFile, layout.source());
        }
        for (PageVariant v : project.variants) {
            if (present(v.sourceFile)) v.sourceFile = located(v.id, v.sourceFile, layout.source());
        }
        for (RequestRecord r : project.requests) {
            if (present(r.rawFile)) r.rawFile = located(r.id, r.rawFile, layout.source());
        }
        for (ResponseRecord r : project.responses) {
            if (present(r.rawFile)) r.rawFile = located(r.id, r.rawFile, layout.source());
        }

        List<Screenshot> removed = new ArrayList<>();
        for (Screenshot s : project.screenshots) {
            String found = locate(s.id, s.imageFile, layout.screenshots());
            if (found != null) {
                s.imageFile = found;
            } else if (options.prune) {
                removed.add(s);
                report.fixed(Category.REFERENCES, PRUNED, "screenshot " + s.id + " (image "
                        + s.imageFile + " is gone)");
            } else {
                report.attention(Category.FILES, MISSING_IMAGES, s.id + " on " + s.pageId + ": "
                        + (s.imageFile.isBlank() ? "(no file recorded)" : s.imageFile));
            }
        }
        if (removed.isEmpty()) return;
        project.screenshots.removeAll(removed);
        for (Screenshot s : removed) {
            for (Page p : project.pages) p.screenshotIds.remove(s.id);
            for (Vulnerability v : project.vulnerabilities) v.screenshotIds.remove(s.id);
            for (Interaction a : project.interactions) if (s.id.equals(a.screenshotId)) a.screenshotId = null;
            project.relationships.removeIf(e -> (e.fromType == EntityType.SCREENSHOT && s.id.equals(e.fromId))
                    || (e.toType == EntityType.SCREENSHOT && s.id.equals(e.toId)));
        }
        edgeSet = null;
        reindex();
    }

    private static boolean present(String ref) { return ref != null && !ref.isBlank(); }

    /** {@link #locate}, reporting and returning null when the file is gone. */
    private String located(String owner, String ref, Path expectedDir) {
        String found = locate(owner, ref, expectedDir);
        if (found == null) missing(owner, ref);
        return found;
    }

    private void missing(String owner, String ref) {
        report.fixed(Category.FILES, MISSING_FILES, owner + ": " + ref);
    }

    /** The project-relative path of the file {@code ref} means, or null if it cannot be found. */
    private String locate(String owner, String ref, Path expectedDir) {
        if (!present(ref)) return null;
        String normalized = ref.trim().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        try {
            Path resolved = layout.root.resolve(normalized).normalize();
            if (resolved.startsWith(layout.root) && Files.isRegularFile(resolved)) {
                String relative = layout.relative(resolved);
                if (!relative.equals(ref)) {
                    report.fixed(Category.FILES, PATHS, owner + ": " + ref + " → " + relative);
                }
                return relative;
            }
            String name = normalized.substring(normalized.lastIndexOf('/') + 1);
            Path byName = expectedDir.resolve(name).normalize();
            if (!name.isEmpty() && byName.startsWith(expectedDir) && Files.isRegularFile(byName)) {
                String relative = layout.relative(byName);
                report.fixed(Category.FILES, RELINKED, owner + ": " + ref + " → " + relative);
                return relative;
            }
        } catch (RuntimeException unusablePath) {
            // Characters this file system cannot represent: treat as missing.
        }
        return null;
    }

    /** Files under source/ and screenshots/ that no record names are dead weight or lost evidence. */
    private void findOrphanFiles() throws IOException {
        Set<String> referenced = new HashSet<>();
        for (Page p : project.pages) {
            referenced.addAll(p.sourceFiles);
            if (p.baseline != null && p.baseline.sourceFile != null) referenced.add(p.baseline.sourceFile);
        }
        for (Resource r : project.resources) if (r.sourceFile != null) referenced.add(r.sourceFile);
        for (PageVariant v : project.variants) if (v.sourceFile != null) referenced.add(v.sourceFile);
        for (RequestRecord r : project.requests) if (r.rawFile != null) referenced.add(r.rawFile);
        for (ResponseRecord r : project.responses) if (r.rawFile != null) referenced.add(r.rawFile);
        for (Screenshot s : project.screenshots) referenced.add(s.imageFile);

        for (Path dir : new Path[]{layout.source(), layout.screenshots()}) {
            if (!Files.isDirectory(dir)) continue;
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dir)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                String relative = layout.relative(file);
                if (referenced.contains(relative)) continue;
                if (options.prune) {
                    Path target = layout.root.resolve(ORPHAN_DIR).resolve(relative);
                    fileOps.add(() -> {
                        Files.createDirectories(target.getParent());
                        Files.move(file, unused(target), StandardCopyOption.REPLACE_EXISTING);
                    });
                    report.fixed(Category.FILES, QUARANTINED, relative);
                } else {
                    report.attention(Category.FILES, ORPHAN_FILES, relative);
                }
            }
        }
    }

    // ============================================================= documents

    private static final Pattern GENERATED = Pattern.compile("(page|form|vuln|res)-\\d+\\.html");

    /** Documents for records that are gone, or for a resource now filed in the other folder. */
    private void findStaleDocuments() throws IOException {
        Map<String, Resource> resources = new HashMap<>();
        for (Resource r : project.resources) resources.put(r.id, r);
        Map<Path, EntityType> dirs = new LinkedHashMap<>();
        dirs.put(layout.pages(), EntityType.PAGE);
        dirs.put(layout.forms(), EntityType.FORM);
        dirs.put(layout.vulnerabilities(), EntityType.VULNERABILITY);
        dirs.put(layout.scripts(), EntityType.RESOURCE);
        dirs.put(layout.files(), EntityType.RESOURCE);

        Set<String> present = new HashSet<>();
        for (Map.Entry<Path, EntityType> dir : dirs.entrySet()) {
            if (!Files.isDirectory(dir.getKey())) continue;
            List<Path> files;
            try (Stream<Path> list = Files.list(dir.getKey())) {
                files = list.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                String name = file.getFileName().toString();
                Matcher m = GENERATED.matcher(name);
                if (!m.matches() || !m.group(1).equals(dir.getValue().slug)) continue;
                String id = name.substring(0, name.length() - ".html".length());
                boolean belongs = exists(dir.getValue(), id);
                if (belongs && dir.getValue() == EntityType.RESOURCE) {
                    Path home = HtmlGenerator.isCodeResource(resources.get(id))
                            ? layout.scripts() : layout.files();
                    belongs = home.equals(dir.getKey());
                }
                if (belongs) {
                    present.add(layout.relative(file));
                } else {
                    fileOps.add(() -> Files.deleteIfExists(file));
                    report.fixed(Category.DOCUMENTS, STALE_DOCS, layout.relative(file));
                }
            }
        }

        List<String> expected = new ArrayList<>();
        expected.add("index.html");
        for (Page p : project.pages) expected.add("pages/" + p.id + ".html");
        for (Form f : project.forms) expected.add("forms/" + f.id + ".html");
        for (Vulnerability v : project.vulnerabilities) expected.add("vulnerabilities/" + v.id + ".html");
        for (Resource r : project.resources) {
            expected.add((HtmlGenerator.isCodeResource(r) ? "scripts/" : "files/") + r.id + ".html");
        }
        for (String doc : expected) {
            if (!present.contains(doc) && !Files.isRegularFile(layout.root.resolve(doc))) {
                report.fixed(Category.DOCUMENTS, MISSING_DOCS, doc);
            }
        }
    }

    /** An endpoint documents what it returns; fill that in from the body saved for it. */
    private void backfillResponseFields() {
        for (Page p : project.pages) {
            if (p.kind != Page.Kind.API || !p.responseFields.isEmpty() || p.sourceFiles.isEmpty()) continue;
            try {
                Path file = layout.root.resolve(p.sourceFiles.get(p.sourceFiles.size() - 1));
                if (Files.size(file) > 2_000_000) continue;
                List<String> fields = ResponseShape.jsonFields(Files.readString(file, StandardCharsets.UTF_8));
                if (fields.isEmpty()) continue;
                p.responseFields.addAll(fields.subList(0, Math.min(fields.size(), 300)));
                report.fixed(Category.RESOURCES, FIELDS, p.id + " " + p.url + " (" + fields.size()
                        + " fields)");
            } catch (IOException | RuntimeException unreadable) {
                // Not text, or not there: nothing to derive.
            }
        }
    }

    /** Merging pages would mean choosing between two sets of the tester's work; only say so. */
    private void reportPossibleDuplicates() {
        Map<String, Page> seen = new HashMap<>();
        for (Page p : project.pages) {
            if (p.url.isBlank()) continue;
            Page first = seen.putIfAbsent(p.method.toUpperCase(Locale.ROOT) + " "
                    + ResourceUrls.canonical(p.url), p);
            if (first != null) {
                report.attention(Category.STRUCTURE, POSSIBLE_DUP_PAGES, first.id + " and " + p.id
                        + ": " + p.method + " " + p.url);
            }
        }
    }

    // ================================================================ applying

    private void apply() throws IOException {
        String stamp = LocalDateTime.now().format(STAMP);
        Path json = layout.projectJson();
        layout.ensureDirectories();
        // Before saving: the store writes through project.json.tmp and would overwrite it.
        if (leftoverTmp != null) {
            Files.move(leftoverTmp, unused(json.resolveSibling("project.json.interrupted-" + stamp)));
        }
        if (recoveredFromTmp || !GSON.toJson(project).equals(loadedJson)) {
            if (Files.exists(json)) {
                Path backup = unused(json.resolveSibling("project.json."
                        + (recoveredFromTmp ? "broken-" : "bak-") + stamp));
                Files.copy(json, backup);
                report.backup = backup;
            }
            store.save(project);
        }
        for (FileOp op : fileOps) op.run();

        Map<Path, Integer> before = documentHashes();
        new HtmlGenerator(project, layout).generateAll();
        for (Map.Entry<Path, Integer> doc : documentHashes().entrySet()) {
            Integer old = before.get(doc.getKey());
            if (old == null) report.documentsNew++;
            else if (!old.equals(doc.getValue())) report.documentsChanged++;
        }
        report.applied = true;
        checkLinks();
    }

    /** A path that does not exist yet: the given one, or it with -1, -2, ... appended. */
    private static Path unused(Path wanted) {
        Path candidate = wanted;
        for (int n = 1; Files.exists(candidate); n++) {
            candidate = wanted.resolveSibling(wanted.getFileName() + "-" + n);
        }
        return candidate;
    }

    private List<Path> documents() throws IOException {
        List<Path> docs = new ArrayList<>();
        for (Path single : new Path[]{layout.indexHtml(), layout.links().resolve("index.html"),
                layout.wordlists().resolve("index.html")}) {
            if (Files.isRegularFile(single)) docs.add(single);
        }
        for (Path dir : new Path[]{layout.pages(), layout.forms(), layout.vulnerabilities(),
                layout.scripts(), layout.files()}) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> list = Files.list(dir)) {
                list.filter(f -> GENERATED.matcher(f.getFileName().toString()).matches())
                        .sorted().forEach(docs::add);
            }
        }
        return docs;
    }

    private Map<Path, Integer> documentHashes() throws IOException {
        Map<Path, Integer> out = new HashMap<>();
        for (Path doc : documents()) out.put(doc, Arrays.hashCode(Files.readAllBytes(doc)));
        return out;
    }

    /**
     * Follow every internal link in the generated documents. Links to the
     * target application (anything with a scheme, or an absolute path) are not
     * ours to check; text inside embedded source is not a link at all, which is
     * why the documents are parsed rather than searched.
     */
    private void checkLinks() throws IOException {
        List<Path> docs = documents();
        report.documentsChecked = docs.size();
        for (Path doc : docs) {
            org.jsoup.nodes.Document html;
            try {
                html = Jsoup.parse(doc.toFile(), "UTF-8");
            } catch (IOException | RuntimeException unreadable) {
                report.brokenLinks.add(layout.relative(doc) + " cannot be read");
                continue;
            }
            for (Element el : html.select("a[href], link[href], script[src], img[src], image[href]")) {
                String target = el.hasAttr("href") ? el.attr("href") : el.attr("src");
                if (target.isBlank() || target.startsWith("#") || target.startsWith("/")
                        || target.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
                    continue;
                }
                report.linksChecked++;
                String path = target.split("[?#]", 2)[0];
                boolean ok;
                try {
                    Path resolved = doc.getParent().resolve(path).normalize();
                    ok = resolved.startsWith(layout.root) && Files.exists(resolved);
                } catch (RuntimeException unusablePath) {
                    ok = false;
                }
                if (!ok) report.brokenLinks.add(layout.relative(doc) + " → " + target);
            }
        }
    }

    // =============================================================== helpers

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

    private static String resolve(String base, String location) {
        try {
            return java.net.URI.create(base).resolve(location.trim()).toString();
        } catch (RuntimeException notAUri) {
            return location.trim();
        }
    }

    private static String joinNotes(String kept, String added) {
        if (added == null || added.isBlank() || kept.contains(added)) return kept;
        return kept.isBlank() ? added : kept + "\n" + added;
    }

    private static String earlier(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String leaf(String url) {
        String path = UrlTemplates.path(url);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 50 ? s.substring(0, 49) + "…" : s;
    }
}
