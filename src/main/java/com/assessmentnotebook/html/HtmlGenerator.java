package com.assessmentnotebook.html;

import com.assessmentnotebook.graph.AppGraph;
import com.assessmentnotebook.graph.TreeNode;
import com.assessmentnotebook.model.*;
import com.assessmentnotebook.store.ProjectLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.assessmentnotebook.html.Html.esc;
import static com.assessmentnotebook.html.Html.orDash;

/**
 * Renders a {@link Project} into the interlinked, self-contained HTML documents
 * that make up the human-facing side of the project directory.
 *
 * <p>Generation is a full, idempotent rebuild from the JSON model. Because ids
 * are stable and every link is relative, regenerating after new discoveries
 * never breaks existing links, and tester notes survive (they live in the
 * model, not in the HTML). Opening {@code index.html} needs only a browser.
 */
public final class HtmlGenerator {
    private final Project project;
    private final ProjectLayout layout;
    private final AppGraph graph;

    // Per-generation indexes so building a document costs its own size, not
    // the size of the whole project (see the per-page lists below).
    private final Map<String, List<Link>> linksByPage = new HashMap<>();
    private final Map<String, List<Screenshot>> screenshotsByPage = new HashMap<>();
    private final Map<String, List<Interaction>> interactionsByPage = new HashMap<>();
    private final Map<String, List<PageVariant>> variantsByPage = new HashMap<>();
    private final Map<String, List<ParameterTest>> testsByParameter = new HashMap<>();
    private final Map<String, List<Note>> notesByTarget = new HashMap<>();

    public HtmlGenerator(Project project, ProjectLayout layout) {
        this.project = project;
        this.layout = layout;
        this.graph = new AppGraph(project);
        for (Link l : project.links) group(linksByPage, l.sourcePageId, l);
        for (Screenshot s : project.screenshots) group(screenshotsByPage, s.pageId, s);
        for (Interaction a : project.interactions) group(interactionsByPage, a.pageId, a);
        for (PageVariant v : project.variants) group(variantsByPage, v.pageId, v);
        for (ParameterTest t : project.parameterTests) group(testsByParameter, t.parameterId, t);
        for (Note n : project.notes) group(notesByTarget, n.targetType + ":" + n.targetId, n);
    }

    private static <T> void group(Map<String, List<T>> m, String key, T value) {
        if (key == null) return;
        m.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    private static <T> List<T> of(Map<String, List<T>> m, String key) {
        return key == null ? List.of() : m.getOrDefault(key, List.of());
    }

    /** Regenerate every document and copy the shared assets. */
    public void generateAll() throws IOException {
        layout.ensureDirectories();
        Assets.copyTo(layout.assets());

        write(layout.indexHtml(), buildIndex());

        for (Page p : project.pages) {
            write(layout.pages().resolve(p.id + ".html"), buildPage(p));
        }
        for (Form f : project.forms) {
            write(layout.forms().resolve(f.id + ".html"), buildForm(f));
        }
        for (Vulnerability v : project.vulnerabilities) {
            write(layout.vulnerabilities().resolve(v.id + ".html"), buildVulnerability(v));
        }
        for (Resource r : project.resources) {
            write(resourceDir(r).resolve(r.id + ".html"), buildResource(r));
        }
        generateLinksIndex();
        generateWordlistsIndex();
    }

    // ---- partial regeneration ----------------------------------------------
    // Each method rewrites exactly one document from the current model. The
    // controller uses them for edits that cannot change the application
    // structure, so a large project does not pay for a full rebuild each time.

    public void generateIndex() throws IOException {
        write(layout.indexHtml(), buildIndex());
    }

    public void generatePage(String pageId) throws IOException {
        Page p = project.findPage(pageId);
        if (p != null) write(layout.pages().resolve(p.id + ".html"), buildPage(p));
    }

    public void generateForm(String formId) throws IOException {
        Form f = project.findForm(formId);
        if (f != null) write(layout.forms().resolve(f.id + ".html"), buildForm(f));
    }

    public void generateVulnerability(String vulnId) throws IOException {
        Vulnerability v = project.findVulnerability(vulnId);
        if (v != null) {
            write(layout.vulnerabilities().resolve(v.id + ".html"), buildVulnerability(v));
        }
    }

    public void generateResource(String resourceId) throws IOException {
        Resource r = project.findResource(resourceId);
        if (r != null) write(resourceDir(r).resolve(r.id + ".html"), buildResource(r));
    }

    public void generateLinksIndex() throws IOException {
        write(layout.links().resolve("index.html"), buildLinksIndex());
    }

    public void generateWordlistsIndex() throws IOException {
        if (!project.interestingStrings.isEmpty()) {
            write(layout.wordlists().resolve("index.html"), buildWordlistsIndex());
        }
    }

    /** Regenerate the document that shows notes for the given entity, if any. */
    public void generateDocFor(EntityType type, String id) throws IOException {
        if (type == null || id == null) return;
        switch (type) {
            case PAGE: generatePage(id); break;
            case FORM: generateForm(id); break;
            case VULNERABILITY: generateVulnerability(id); break;
            case RESOURCE: generateResource(id); break;
            default: break; // project-level and other notes show on the index only
        }
    }

    private Path resourceDir(Resource r) {
        return isCodeResource(r) ? layout.scripts() : layout.files();
    }

    private static boolean isCodeResource(Resource r) {
        return r.type == Resource.Type.SCRIPT || r.type == Resource.Type.STYLESHEET
                || r.type == Resource.Type.XHR || r.type == Resource.Type.API;
    }

    // ================================================================ index

    private String buildIndex() {
        StringBuilder b = new StringBuilder();
        TargetInfo t = project.target;

        b.append(panelOpen("TARGET // " + esc(project.name)));
        b.append("<table class=\"kv\">");
        row(b, "Domain", orDash(t.domain));
        row(b, "Main URL", link(t.mainUrl));
        row(b, "Assessment", orDash(t.assessmentName));
        row(b, "Date", orDash(t.assessmentDate));
        if (t.server != null && !t.server.isBlank()) row(b, "Server", orDash(t.server));
        if (t.frameworks != null && !t.frameworks.isBlank()) row(b, "Frameworks", orDash(t.frameworks));
        if (t.authentication != null && !t.authentication.isBlank()) {
            row(b, "Authentication", orDash(t.authentication));
        }
        row(b, "Pages", String.valueOf(project.pages.size()));
        row(b, "Forms", String.valueOf(project.forms.size()));
        row(b, "Findings", String.valueOf(project.vulnerabilities.size()));
        b.append("</table>");
        if (!t.notes.isBlank()) b.append("<p class=\"notes\">").append(esc(t.notes)).append("</p>");
        b.append(panelClose());

        // Technologies, grouped.
        b.append(panelOpen("TECHNOLOGIES"));
        Map<Technology.Category, List<Technology>> byCat = project.technologiesByCategory();
        if (byCat.isEmpty()) {
            b.append(empty("No technologies recorded yet."));
        } else {
            b.append("<table class=\"grid\"><thead><tr><th>Category</th><th>Name</th>"
                    + "<th>Version</th><th>Confidence</th><th>Evidence</th>"
                    + "<th>Updated</th></tr></thead><tbody>");
            for (Technology.Category cat : Technology.Category.values()) {
                List<Technology> list = byCat.get(cat);
                if (list == null) continue;
                for (Technology tech : list) {
                    String conf = tech.confidence == null ? "—" : tech.confidence.label;
                    String confClass = "conf-" + (tech.confidence == null ? "medium"
                            : tech.confidence.name().toLowerCase());
                    StringBuilder ev = new StringBuilder();
                    List<String> lines = tech.evidenceLines();
                    for (int i = 0; i < lines.size(); i++) {
                        if (i > 0) ev.append("<br>");
                        ev.append("<code>").append(esc(lines.get(i))).append("</code>");
                    }
                    if (lines.isEmpty()) ev.append("—");
                    if (tech.userEdited) ev.append(" <span class=\"dim\">(tester-verified)</span>");
                    b.append("<tr><td>").append(esc(cat.label)).append("</td><td>")
                            .append(orDash(tech.name)).append("</td><td>")
                            .append(orDash(tech.version)).append("</td><td class=\"")
                            .append(confClass).append("\">").append(esc(conf)).append("</td><td>")
                            .append(ev).append("</td><td class=\"dim\">")
                            .append(orDash(tech.lastUpdated)).append("</td></tr>");
                }
            }
            b.append("</tbody></table>");
        }
        b.append(panelClose());

        // Application tree.
        b.append(panelOpen("APPLICATION STRUCTURE"));
        if (project.pages.isEmpty()) {
            b.append(empty("No pages registered yet."));
        } else {
            TreeNode root = graph.buildTree();
            b.append("<ul class=\"tree root\">");
            b.append(renderTreeNode(root, ""));
            b.append("</ul>");
        }
        b.append(panelClose());

        // Discovery paths (how pages lead to one another).
        StringBuilder disc = new StringBuilder();
        for (Page p : project.pages) {
            List<String> paths = graph.discoveryPaths(p.id);
            if (paths.isEmpty()) continue;
            disc.append("<div class=\"disc\"><a href=\"").append("pages/").append(p.id)
                    .append(".html\">").append(orDash(shortUrl(p.url))).append("</a><ul>");
            for (String path : paths) disc.append("<li>").append(esc(path)).append("</li>");
            disc.append("</ul></div>");
        }
        if (disc.length() > 0) {
            b.append(panelOpen("DISCOVERY PATHS"));
            b.append(disc);
            b.append(panelClose());
        }

        // Findings.
        b.append(panelOpen("VULNERABILITIES"));
        if (project.vulnerabilities.isEmpty()) {
            b.append(empty("No findings recorded."));
        } else {
            b.append("<table class=\"grid\"><thead><tr><th>ID</th><th>Severity</th>"
                    + "<th>Title</th><th>Status</th><th>Affected</th></tr></thead><tbody>");
            List<Vulnerability> vulns = project.vulnerabilities.stream()
                    .sorted(Comparator.comparingInt((Vulnerability v) -> v.severity.ordinal()).reversed())
                    .toList();
            for (Vulnerability v : vulns) {
                b.append("<tr><td>").append(esc(v.id)).append("</td><td>")
                        .append(severityBadge(v.severity)).append("</td><td><a href=\"")
                        .append("vulnerabilities/").append(v.id).append(".html\">")
                        .append(orDash(v.title)).append("</a></td><td>")
                        .append(esc(v.status.label)).append("</td><td>")
                        .append(orDash(shortUrl(v.affectedUrl))).append("</td></tr>");
            }
            b.append("</tbody></table>");
        }
        b.append(panelClose());

        // Recent observations.
        b.append(panelOpen("RECENT OBSERVATIONS"));
        List<Note> recent = project.notes.stream()
                .sorted(Comparator.comparing((Note n) -> n.createdAt == null ? "" : n.createdAt).reversed())
                .limit(10).toList();
        if (recent.isEmpty()) {
            b.append(empty("No observations yet."));
        } else {
            b.append("<ul class=\"notelist\">");
            for (Note n : recent) {
                b.append("<li><span class=\"tag tag-").append(n.kind.name().toLowerCase())
                        .append("\">").append(esc(n.kind.label)).append("</span> ")
                        .append(esc(n.text)).append(" <span class=\"ts\">")
                        .append(orDash(n.createdAt)).append("</span></li>");
            }
            b.append("</ul>");
        }
        b.append(panelClose());

        // Interesting strings / wordlists (spec §8).
        if (!project.interestingStrings.isEmpty()) {
            b.append(panelOpen("WORDLISTS"));
            b.append("<p><a href=\"wordlists/index.html\">Interesting strings ("
                    + project.interestingStrings.size() + ") →</a></p>");
            b.append(panelClose());
        }

        return shell(project.name + " // overview", "", b.toString(), "index");
    }

    private String buildWordlistsIndex() {
        StringBuilder b = new StringBuilder();
        String up = "../";
        b.append(panelOpen("WORDLISTS // interesting strings"));
        b.append("<p class=\"dim\">Marked strings, bucketed for export to other "
                + "authorized tools. Exported files live in <code>wordlists/*.txt</code>.</p>");
        b.append(panelClose());
        for (com.assessmentnotebook.model.InterestingString.Category cat
                : com.assessmentnotebook.model.InterestingString.Category.values()) {
            List<com.assessmentnotebook.model.InterestingString> items = project.interestingStrings
                    .stream().filter(s -> s.category == cat).toList();
            if (items.isEmpty()) continue;
            b.append(panelOpen(esc(cat.label) + " (" + items.size() + ") — "
                    + esc(cat.slug) + ".txt"));
            b.append("<table class=\"grid\"><thead><tr><th>Value</th><th>Source</th>"
                    + "<th>Element</th><th>Context</th></tr></thead><tbody>");
            for (com.assessmentnotebook.model.InterestingString s : items) {
                Page src = s.sourcePageId == null ? null : project.findPage(s.sourcePageId);
                String source = src == null ? "—"
                        : "<a href=\"" + up + "pages/" + src.id + ".html\">"
                          + orDash(shortUrl(src.url)) + "</a>";
                b.append("<tr><td><code>").append(esc(s.value)).append("</code></td><td>")
                        .append(source).append("</td><td>").append(orDash(s.sourceElement))
                        .append("</td><td class=\"dim\">").append(orDash(s.context))
                        .append("</td></tr>");
            }
            b.append("</tbody></table>");
            b.append(panelClose());
        }
        return shell("wordlists", up, b.toString(), "wordlists");
    }

    private String renderTreeNode(TreeNode node, String up) {
        StringBuilder b = new StringBuilder();
        String label = node.label.isEmpty() ? "/" : esc(node.label);
        b.append("<li>");
        if (node.isPage()) {
            Page p = project.findPage(node.pageId);
            String badge = p != null && p.method != null && !p.method.equalsIgnoreCase("GET")
                    ? " <span class=\"method\">" + esc(p.method) + "</span>" : "";
            b.append("<a href=\"").append(up).append("pages/").append(node.pageId)
                    .append(".html\">").append(label).append("</a>").append(badge);
        } else {
            b.append("<span class=\"branch\">").append(label).append("</span>");
        }
        if (!node.children.isEmpty()) {
            b.append("<ul>");
            for (TreeNode child : node.sortedChildren()) {
                b.append(renderTreeNode(child, up));
            }
            b.append("</ul>");
        }
        b.append("</li>");
        return b.toString();
    }

    // ================================================================ page

    private String buildPage(Page p) {
        StringBuilder b = new StringBuilder();
        String up = "../";

        b.append(panelOpen("PAGE // " + esc(p.id)));
        b.append("<table class=\"kv\">");
        row(b, "URL", link(p.url));
        row(b, "Title", orDash(p.title));
        row(b, "Method", esc(p.method));
        row(b, "Status", p.statusCode == 0 ? "—" : String.valueOf(p.statusCode));
        row(b, "Content-Type", orDash(p.contentType));
        String discovery = p.discoverySourceKind == null ? "—" : esc(p.discoverySourceKind.label);
        if (p.discoverySource != null && !p.discoverySource.isBlank()) {
            discovery += " <span class=\"dim\">(" + esc(p.discoverySource) + ")</span>";
        }
        row(b, "Discovery source", discovery);
        row(b, "First seen", orDash(p.firstSeen));
        row(b, "Last seen", orDash(p.lastSeen));
        b.append("</table>");
        if (!p.notes.isBlank()) b.append("<p class=\"notes\">").append(esc(p.notes)).append("</p>");
        b.append(panelClose());

        // Discovery / parents.
        List<String> disc = graph.discoveryPaths(p.id);
        if (!disc.isEmpty()) {
            b.append(panelOpen("DISCOVERED THROUGH"));
            b.append("<ul class=\"plain\">");
            for (String d : disc) b.append("<li>").append(esc(d)).append("</li>");
            b.append("</ul>").append(panelClose());
        }

        // Screenshots as a stepped sequence.
        List<Screenshot> shots = of(screenshotsByPage, p.id).stream()
                .sorted(Comparator.comparingInt(s -> s.sequence)).toList();
        if (!shots.isEmpty()) {
            b.append(panelOpen("VISUAL STATES"));
            b.append("<div class=\"stepper\" data-count=\"").append(shots.size()).append("\">");
            b.append("<div class=\"frames\">");
            int i = 0;
            for (Screenshot s : shots) {
                b.append("<figure class=\"frame\" data-index=\"").append(i).append("\">");
                b.append(screenshotMarkup(s, up));
                b.append("<figcaption>")
                        .append("State ").append(i + 1).append(": ").append(orDash(s.stateDescription));
                if (s.relatedInteractionId != null) {
                    Interaction act = project.findInteraction(s.relatedInteractionId);
                    if (act != null) b.append(" — ").append(esc(act.action));
                }
                b.append("</figcaption></figure>");
                i++;
            }
            b.append("</div><div class=\"steps\">");
            b.append("<button class=\"step-prev\">◀ PREV</button>");
            b.append("<span class=\"step-label\"></span>");
            b.append("<button class=\"step-next\">NEXT ▶</button>");
            b.append("</div></div>").append(panelClose());
        }

        // Interactions.
        List<Interaction> acts = of(interactionsByPage, p.id);
        if (!acts.isEmpty()) {
            b.append(panelOpen("INTERACTIONS"));
            b.append("<table class=\"grid\"><thead><tr><th>Action</th><th>Observed behavior</th>"
                    + "<th>Request</th></tr></thead><tbody>");
            for (Interaction a : acts) {
                b.append("<tr><td>").append(orDash(a.action)).append("</td><td>")
                        .append(orDash(a.observedBehavior)).append("</td><td>")
                        .append(a.requestId == null ? "—" : esc(a.requestId)).append("</td></tr>");
            }
            b.append("</tbody></table>").append(panelClose());
        }

        // Forms.
        b.append(panelOpen("FORMS"));
        if (p.formIds.isEmpty()) {
            b.append(empty("No forms documented on this page."));
        } else {
            b.append("<ul class=\"plain\">");
            for (String fid : p.formIds) {
                Form f = project.findForm(fid);
                if (f == null) continue;
                b.append("<li><a href=\"").append(up).append("forms/").append(f.id)
                        .append(".html\">").append(esc(f.method)).append(' ')
                        .append(orDash(shortUrl(f.action))).append("</a> ")
                        .append("<span class=\"muted\">").append(f.parameterIds.size())
                        .append(" params</span></li>");
            }
            b.append("</ul>");
        }
        b.append(panelClose());

        // Links.
        b.append(panelOpen("LINKS"));
        List<Link> pageLinks = of(linksByPage, p.id);
        if (pageLinks.isEmpty()) {
            b.append(empty("No links documented."));
        } else {
            b.append("<table class=\"grid\"><thead><tr><th>Text</th><th>Destination</th>"
                    + "<th>Type</th></tr></thead><tbody>");
            for (Link l : pageLinks) {
                String dest = l.destinationPageId != null
                        ? "<a href=\"" + up + "pages/" + l.destinationPageId + ".html\">"
                        + orDash(shortUrl(l.destinationUrl)) + "</a>"
                        : link(l.destinationUrl);
                b.append("<tr><td>").append(orDash(l.visibleText)).append("</td><td>")
                        .append(dest).append("</td><td>").append(orDash(l.elementType))
                        .append("</td></tr>");
            }
            b.append("</tbody></table>");
        }
        b.append(panelClose());

        // Resources.
        b.append(panelOpen("RESOURCES"));
        if (p.resourceIds.isEmpty()) {
            b.append(empty("No resources associated."));
        } else {
            b.append("<ul class=\"plain\">");
            for (String rid : p.resourceIds) {
                Resource r = project.findResource(rid);
                if (r == null) continue;
                String dir = isCodeResource(r) ? "scripts/" : "files/";
                b.append("<li><span class=\"tag\">").append(esc(r.type.label)).append("</span> ")
                        .append("<a href=\"").append(up).append(dir).append(r.id).append(".html\">")
                        .append(orDash(shortUrl(r.url))).append("</a></li>");
            }
            b.append("</ul>");
        }
        b.append(panelClose());

        // Variants and the differences between them (spec §12, §13).
        appendVariants(b, p, up);

        // Source files (embedded with highlighting, and downloadable).
        if (!p.sourceFiles.isEmpty()) {
            b.append(panelOpen("SOURCE"));
            for (String src : p.sourceFiles) b.append(embeddedSource(src, up));
            b.append(panelClose());
        }

        b.append(notesPanel(EntityType.PAGE, p.id));
        return shell(shortUrl(p.url) + " // page", up, b.toString(), "page");
    }

    private void appendVariants(StringBuilder b, Page p, String up) {
        List<com.assessmentnotebook.model.PageVariant> vs = of(variantsByPage, p.id);
        if (vs.isEmpty()) return;
        b.append(panelOpen("VARIANTS (" + vs.size() + ")"));
        b.append("<table class=\"grid\"><thead><tr><th>Label</th><th>Conditions</th>"
                + "<th>Status</th><th>Length</th><th>Title</th><th>Reflected</th>"
                + "</tr></thead><tbody>");
        for (com.assessmentnotebook.model.PageVariant v : vs) {
            b.append("<tr><td>").append(orDash(v.label)).append("</td><td class=\"dim\"><code>")
                    .append(esc(conditions(v))).append("</code></td><td>")
                    .append(v.statusCode == 0 ? "—" : String.valueOf(v.statusCode)).append("</td><td>")
                    .append(v.bodyLength).append("</td><td>").append(orDash(v.title)).append("</td><td>")
                    .append(v.reflectedInputNames.isEmpty() ? "—"
                            : esc(String.join(", ", v.reflectedInputNames)))
                    .append("</td></tr>");
        }
        b.append("</tbody></table>");
        b.append(panelClose());

        List<com.assessmentnotebook.analyze.VariantDiff.Comparison> comps =
                com.assessmentnotebook.analyze.VariantDiff.compareToBaseline(vs);
        if (!comps.isEmpty()) {
            b.append(panelOpen("DYNAMIC DIFFERENCES (vs. baseline)"));
            for (var c : comps) {
                com.assessmentnotebook.model.PageVariant v = project.findVariant(c.variantId);
                b.append("<div class=\"diff\"><h4>")
                        .append(esc(v == null ? c.variantId : label(v))).append("</h4>");
                b.append("<p class=\"dim\">Inputs changed:</p><ul class=\"plain\">");
                if (c.inputDifferences.isEmpty()) b.append("<li>—</li>");
                for (String d : c.inputDifferences) {
                    b.append("<li><code>").append(esc(d)).append("</code></li>");
                }
                b.append("</ul><p class=\"dim\">Behavior changes:</p><ul class=\"plain\">");
                if (c.outputDifferences.isEmpty()) b.append("<li>no observable change</li>");
                for (String d : c.outputDifferences) {
                    b.append("<li><code>").append(esc(d)).append("</code></li>");
                }
                b.append("</ul></div>");
            }
            b.append(panelClose());
        }
    }

    /** An image, or an SVG overlay with annotation rectangles (spec §9). */
    private String screenshotMarkup(Screenshot s, String up) {
        String src = up + esc(s.imageFile);
        String alt = esc(s.stateDescription);
        if (s.annotations == null || s.annotations.isEmpty()
                || s.imageWidth <= 0 || s.imageHeight <= 0) {
            return "<img src=\"" + src + "\" alt=\"" + alt + "\">";
        }
        StringBuilder b = new StringBuilder();
        b.append("<svg class=\"annotated\" viewBox=\"0 0 ").append(s.imageWidth).append(' ')
                .append(s.imageHeight).append("\" xmlns=\"http://www.w3.org/2000/svg\" ")
                .append("preserveAspectRatio=\"xMidYMid meet\" role=\"img\">");
        b.append("<image href=\"").append(src).append("\" x=\"0\" y=\"0\" width=\"")
                .append(s.imageWidth).append("\" height=\"").append(s.imageHeight).append("\"/>");
        int strokeW = Math.max(2, s.imageWidth / 400);
        int fontSize = Math.max(12, s.imageWidth / 60);
        for (com.assessmentnotebook.model.Annotation a : s.annotations) {
            b.append("<rect x=\"").append(a.x).append("\" y=\"").append(a.y)
                    .append("\" width=\"").append(a.width).append("\" height=\"").append(a.height)
                    .append("\" fill=\"none\" stroke=\"#e8564b\" stroke-width=\"").append(strokeW)
                    .append("\"/>");
            if (a.label != null && !a.label.isBlank()) {
                int ty = a.y > fontSize + 4 ? a.y - 4 : a.y + a.height + fontSize;
                b.append("<text x=\"").append(a.x).append("\" y=\"").append(ty)
                        .append("\" fill=\"#e8564b\" font-size=\"").append(fontSize)
                        .append("\" font-family=\"monospace\">").append(esc(a.label)).append("</text>");
            }
        }
        b.append("</svg>");
        return b.toString();
    }

    private static String label(com.assessmentnotebook.model.PageVariant v) {
        return v.label == null || v.label.isBlank() ? v.id : v.label;
    }

    private static String conditions(com.assessmentnotebook.model.PageVariant v) {
        StringBuilder s = new StringBuilder();
        v.queryParams.forEach((k, val) -> s.append(k).append("=").append(val).append(" "));
        v.bodyParams.forEach((k, val) -> s.append(k).append("=").append(val).append(" "));
        v.jsonParams.forEach((k, val) -> s.append(k).append(":").append(val).append(" "));
        if (!v.authContext.isBlank()) s.append("[auth:").append(v.authContext).append("]");
        return s.toString().trim();
    }

    // ================================================================ form

    private String buildForm(Form f) {
        StringBuilder b = new StringBuilder();
        String up = "../";
        Page page = project.findPage(f.pageId);

        b.append(panelOpen("FORM // " + esc(f.id)));
        b.append("<table class=\"kv\">");
        if (page != null) {
            row(b, "Page", "<a href=\"" + up + "pages/" + page.id + ".html\">"
                    + orDash(shortUrl(page.url)) + "</a>");
        }
        row(b, "Action", link(f.action));
        row(b, "Method", esc(f.method));
        row(b, "Encoding", orDash(f.encType));
        row(b, "Identifier", orDash(f.formIdentifier));
        row(b, "Location", orDash(f.locationOnPage));
        b.append("</table>");
        if (!f.notes.isBlank()) b.append("<p class=\"notes\">").append(esc(f.notes)).append("</p>");
        b.append(panelClose());

        b.append(panelOpen("PARAMETERS"));
        if (f.parameterIds.isEmpty()) {
            b.append(empty("No parameters documented."));
        } else {
            for (String pid : f.parameterIds) {
                Parameter param = project.findParameter(pid);
                if (param == null) continue;
                b.append("<div class=\"param\"><h4>").append(orDash(param.name))
                        .append(" <span class=\"muted\">").append(orDash(param.inputType))
                        .append(param.required ? " · required" : "").append("</span></h4>");
                b.append("<table class=\"kv\">");
                row(b, "Default", orDash(param.defaultValue));
                row(b, "Purpose", orDash(param.purpose));
                row(b, "Observed values", param.observedValues.isEmpty() ? "—"
                        : esc(String.join(", ", param.observedValues)));
                b.append("</table>");
                if (!param.reflections.isEmpty()) {
                    b.append("<div class=\"reflect\"><h5>Reflections</h5>"
                            + "<table class=\"grid\"><thead><tr><th>Submitted</th>"
                            + "<th>Context</th><th>Location</th><th>Excerpt</th></tr></thead><tbody>");
                    for (Reflection ref : param.reflections) {
                        b.append("<tr><td>").append(esc(ref.submittedValue)).append("</td><td>")
                                .append(esc(ref.context.label)).append("</td><td>")
                                .append(orDash(ref.location)).append("</td><td><code>")
                                .append(esc(ref.excerpt)).append("</code></td></tr>");
                    }
                    b.append("</tbody></table></div>");
                }
                appendParameterTests(b, pid);
                b.append("</div>");
            }
        }
        b.append(panelClose());

        b.append(notesPanel(EntityType.FORM, f.id));
        return shell("form " + f.id, up, b.toString(), "form");
    }

    private void appendParameterTests(StringBuilder b, String parameterId) {
        List<com.assessmentnotebook.model.ParameterTest> tests = of(testsByParameter, parameterId);
        if (tests.isEmpty()) return;
        com.assessmentnotebook.analyze.ParameterAnalysis.Summary sum =
                com.assessmentnotebook.analyze.ParameterAnalysis.summarize(tests);
        b.append("<div class=\"ptests\"><h5>Parameter tests</h5>");
        StringBuilder tags = new StringBuilder();
        if (sum.required) tags.append("required "); else if (sum.acceptsEmpty) tags.append("accepts-empty ");
        if (sum.acceptsOmitted) tags.append("optional ");
        if (sum.reflected) tags.append("reflected ");
        if (sum.changesStatus) tags.append("status-varies ");
        if (sum.changesLength) tags.append("length-varies ");
        if (sum.lengthRestricted) tags.append("length-limited ");
        if (tags.length() > 0) {
            b.append("<p class=\"dim\">Summary: ").append(esc(tags.toString().trim())).append("</p>");
        }
        b.append("<table class=\"grid\"><thead><tr><th>Probe</th><th>Sent</th><th>Status</th>"
                + "<th>Length</th><th>Observation</th><th>Class</th></tr></thead><tbody>");
        for (com.assessmentnotebook.model.ParameterTest t : tests) {
            String cls = t.classification == null ? "" : t.classification.label;
            b.append("<tr><td>").append(orDash(t.probeLabel)).append("</td><td><code>")
                    .append(esc(t.sentValue)).append("</code></td><td>").append(t.responseStatus)
                    .append("</td><td>").append(t.responseLength).append("</td><td class=\"dim\">")
                    .append(orDash(t.observation)).append("</td><td>").append(esc(cls))
                    .append("</td></tr>");
        }
        b.append("</tbody></table></div>");
    }

    // ============================================================ resource

    private String buildResource(Resource r) {
        StringBuilder b = new StringBuilder();
        String up = "../";
        b.append(panelOpen("RESOURCE // " + esc(r.id)));
        b.append("<table class=\"kv\">");
        row(b, "Type", esc(r.type.label));
        row(b, "URL", link(r.url));
        b.append("</table>");
        if (!r.notes.isBlank()) b.append("<p class=\"notes\">").append(esc(r.notes)).append("</p>");
        b.append(panelClose());

        if (r.sourceFile != null && !r.sourceFile.isBlank()) {
            b.append(panelOpen("CAPTURED SOURCE"));
            b.append(embeddedSource(r.sourceFile, up));
            b.append(panelClose());
        }

        b.append(panelOpen("LOADED BY"));
        if (r.pageIds.isEmpty()) {
            b.append(empty("Not yet associated with a page."));
        } else {
            b.append("<ul class=\"plain\">");
            for (String pid : r.pageIds) {
                Page p = project.findPage(pid);
                if (p == null) continue;
                b.append("<li><a href=\"").append(up).append("pages/").append(p.id)
                        .append(".html\">").append(orDash(shortUrl(p.url))).append("</a></li>");
            }
            b.append("</ul>");
        }
        b.append(panelClose());
        b.append(notesPanel(EntityType.RESOURCE, r.id));
        return shell("resource " + r.id, up, b.toString(), "resource");
    }

    // ======================================================= vulnerability

    private String buildVulnerability(Vulnerability v) {
        StringBuilder b = new StringBuilder();
        String up = "../";
        b.append(panelOpen("FINDING // " + esc(v.id)));
        b.append("<table class=\"kv\">");
        row(b, "Title", orDash(v.title));
        row(b, "Severity", severityBadge(v.severity));
        row(b, "Status", esc(v.status.label));
        row(b, "Affected URL", link(v.affectedUrl));
        row(b, "Component", orDash(v.affectedComponent));
        b.append("</table>").append(panelClose());

        b.append(section("DESCRIPTION", v.description));
        b.append(section("TECHNICAL OBSERVATION", v.technicalObservation));
        b.append(section("STEPS TO REPRODUCE", v.stepsToReproduce));
        b.append(section("IMPACT", v.impact));
        b.append(section("REMEDIATION", v.remediation));

        // Related components via relationships.
        List<Relationship> edges = graph.outgoing(EntityType.VULNERABILITY, v.id);
        if (!edges.isEmpty()) {
            b.append(panelOpen("RELATED COMPONENTS"));
            b.append("<ul class=\"plain\">");
            for (Relationship e : edges) {
                b.append("<li>").append(esc(e.kind)).append(" → ")
                        .append(relLink(up, e.toType, e.toId)).append("</li>");
            }
            b.append("</ul>").append(panelClose());
        }

        // Screenshot evidence.
        if (!v.screenshotIds.isEmpty()) {
            b.append(panelOpen("EVIDENCE"));
            for (String sid : v.screenshotIds) {
                Screenshot s = project.findScreenshot(sid);
                if (s == null) continue;
                b.append("<figure class=\"evidence\"><img src=\"").append(up).append(esc(s.imageFile))
                        .append("\" alt=\"").append(esc(s.stateDescription))
                        .append("\"><figcaption>").append(orDash(s.stateDescription))
                        .append("</figcaption></figure>");
            }
            b.append(panelClose());
        }

        b.append(notesPanel(EntityType.VULNERABILITY, v.id));
        return shell("finding " + v.id, up, b.toString(), "vuln");
    }

    // =============================================================== links

    private String buildLinksIndex() {
        StringBuilder b = new StringBuilder();
        String up = "../";
        b.append(panelOpen("LINKS"));
        if (project.links.isEmpty()) {
            b.append(empty("No links recorded."));
        } else {
            b.append("<table class=\"grid\"><thead><tr><th>Source</th><th>Text</th>"
                    + "<th>Destination</th><th>Type</th><th>Method</th></tr></thead><tbody>");
            for (Link l : project.links) {
                Page src = project.findPage(l.sourcePageId);
                String srcCell = src != null
                        ? "<a href=\"" + up + "pages/" + src.id + ".html\">" + orDash(shortUrl(src.url)) + "</a>"
                        : "—";
                String dest = l.destinationPageId != null
                        ? "<a href=\"" + up + "pages/" + l.destinationPageId + ".html\">"
                        + orDash(shortUrl(l.destinationUrl)) + "</a>"
                        : link(l.destinationUrl);
                b.append("<tr><td>").append(srcCell).append("</td><td>").append(orDash(l.visibleText))
                        .append("</td><td>").append(dest).append("</td><td>").append(orDash(l.elementType))
                        .append("</td><td>").append(orDash(l.method)).append("</td></tr>");
            }
            b.append("</tbody></table>");
        }
        b.append(panelClose());
        return shell("links", up, b.toString(), "links");
    }

    // ============================================================= shared

    private String relLink(String up, EntityType type, String id) {
        switch (type) {
            case PAGE: return "<a href=\"" + up + "pages/" + id + ".html\">" + esc(id) + "</a>";
            case FORM: return "<a href=\"" + up + "forms/" + id + ".html\">" + esc(id) + "</a>";
            case VULNERABILITY: return "<a href=\"" + up + "vulnerabilities/" + id + ".html\">" + esc(id) + "</a>";
            case RESOURCE:
                Resource r = project.findResource(id);
                String dir = (r != null && isCodeResource(r)) ? "scripts/" : "files/";
                return "<a href=\"" + up + dir + id + ".html\">" + esc(id) + "</a>";
            default: return esc(type.name().toLowerCase() + " " + id);
        }
    }

    private String notesPanel(EntityType type, String id) {
        List<Note> notes = of(notesByTarget, type + ":" + id);
        if (notes.isEmpty()) return "";
        StringBuilder b = new StringBuilder(panelOpen("NOTES & OBSERVATIONS"));
        b.append("<ul class=\"notelist\">");
        for (Note n : notes) {
            b.append("<li><span class=\"tag tag-").append(n.kind.name().toLowerCase())
                    .append("\">").append(esc(n.kind.label)).append("</span> ")
                    .append(esc(n.text)).append(" <span class=\"ts\">").append(orDash(n.createdAt))
                    .append("</span></li>");
        }
        b.append("</ul>");
        return b.append(panelClose()).toString();
    }

    private String section(String title, String text) {
        if (text == null || text.isBlank()) return "";
        return panelOpen(title) + "<p class=\"prose\">"
                + esc(text).replace("\n", "<br>") + "</p>" + panelClose();
    }

    /**
     * Embed a captured source file inline (escaped, highlighted client-side)
     * with a download link to the untouched original. Content is inlined at
     * generation time so it renders from {@code file://} without a server;
     * very large files are left as a download only.
     */
    private String embeddedSource(String rel, String up) {
        String href = up + rel;
        String lang = langOf(rel);
        StringBuilder b = new StringBuilder("<div class=\"srcfile\">");
        b.append("<div class=\"srcactions\"><span class=\"srcname\">").append(esc(rel))
                .append("</span><a class=\"btn\" href=\"").append(esc(href))
                .append("\" download>DOWNLOAD ORIGINAL</a></div>");
        try {
            Path abs = layout.root.resolve(rel);
            long size = Files.size(abs);
            if (size > 256 * 1024) {
                b.append("<p class=\"empty\">Source is ").append(size / 1024)
                        .append(" KB — use the download to view the original.</p>");
            } else {
                String content = Files.readString(abs, StandardCharsets.UTF_8);
                b.append("<pre class=\"source\"><code class=\"lang-").append(esc(lang))
                        .append("\">").append(esc(content)).append("</code></pre>");
            }
        } catch (IOException e) {
            b.append("<p class=\"empty\">Source file not found: ").append(esc(rel)).append("</p>");
        }
        return b.append("</div>").toString();
    }

    private static String langOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "js";
        if (n.endsWith(".css")) return "css";
        if (n.endsWith(".json")) return "json";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "html";
        return "text";
    }

    private static void row(StringBuilder b, String k, String vHtml) {
        b.append("<tr><th>").append(esc(k)).append("</th><td>").append(vHtml).append("</td></tr>");
    }

    private static String link(String url) {
        if (url == null || url.isBlank()) return "—";
        return "<a href=\"" + esc(url) + "\" rel=\"noreferrer\">" + esc(url) + "</a>";
    }

    private static String severityBadge(Vulnerability.Severity s) {
        return "<span class=\"sev sev-" + s.name().toLowerCase() + "\">" + s.name() + "</span>";
    }

    private static String shortUrl(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            java.net.URI u = java.net.URI.create(url.trim());
            String path = u.getPath();
            if (path == null || path.isEmpty()) path = "/";
            String q = u.getQuery() != null ? "?" + u.getQuery() : "";
            return path + q;
        } catch (RuntimeException e) {
            return url;
        }
    }

    private static String empty(String msg) {
        return "<p class=\"empty\">" + esc(msg) + "</p>";
    }

    private static String panelOpen(String title) {
        return "<section class=\"panel\"><h2 class=\"panel-title\">" + esc(title) + "</h2><div class=\"panel-body\">";
    }

    private static String panelClose() {
        return "</div></section>";
    }

    /** The shared page shell: head, CRT frame, header bar, footer. */
    private String shell(String title, String up, String body, String kind) {
        return "<!DOCTYPE html>\n<html lang=\"en\" data-kind=\"" + esc(kind) + "\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + esc(title) + "</title>\n"
                + "<link rel=\"stylesheet\" href=\"" + up + "assets/retro.css\">\n"
                + "</head>\n<body>\n<div class=\"crt\">\n"
                + "<header class=\"topbar\"><a class=\"home\" href=\"" + up + "index.html\">"
                + "▣ ASSESSMENT NOTEBOOK</a><span class=\"crumb\">" + esc(title) + "</span></header>\n"
                + "<main>\n" + body + "\n</main>\n"
                + "<footer class=\"botbar\"><span>" + esc(project.name)
                + "</span><span class=\"scan\"></span></footer>\n</div>\n"
                + "<script src=\"" + up + "assets/highlight.js\"></script>\n"
                + "<script src=\"" + up + "assets/app.js\"></script>\n</body>\n</html>\n";
    }

    /** Write the document, skipping the write when the file already has this content. */
    private static void write(Path path, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        try {
            if (Files.size(path) == bytes.length && Arrays.equals(Files.readAllBytes(path), bytes)) {
                return;
            }
        } catch (IOException notThere) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, bytes);
    }
}
