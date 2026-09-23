package com.assessmentnotebook.html;

import com.assessmentnotebook.graph.AppGraph;
import com.assessmentnotebook.graph.TreeNode;
import com.assessmentnotebook.model.*;
import com.assessmentnotebook.store.ProjectLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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

    public HtmlGenerator(Project project, ProjectLayout layout) {
        this.project = project;
        this.layout = layout;
        this.graph = new AppGraph(project);
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
            Path dir = isCodeResource(r) ? layout.scripts() : layout.files();
            write(dir.resolve(r.id + ".html"), buildResource(r));
        }
        write(layout.links().resolve("index.html"), buildLinksIndex());
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
                    + "<th>Version</th><th>Evidence</th></tr></thead><tbody>");
            for (Technology.Category cat : Technology.Category.values()) {
                List<Technology> list = byCat.get(cat);
                if (list == null) continue;
                for (Technology tech : list) {
                    b.append("<tr><td>").append(esc(cat.label)).append("</td><td>")
                            .append(orDash(tech.name)).append("</td><td>")
                            .append(orDash(tech.version)).append("</td><td>")
                            .append(orDash(tech.evidence)).append("</td></tr>");
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

        return shell(project.name + " // overview", "", b.toString(), "index");
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
        row(b, "Discovery source", orDash(p.discoverySource));
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
        List<Screenshot> shots = project.screenshots.stream()
                .filter(s -> p.id.equals(s.pageId))
                .sorted(Comparator.comparingInt(s -> s.sequence)).toList();
        if (!shots.isEmpty()) {
            b.append(panelOpen("VISUAL STATES"));
            b.append("<div class=\"stepper\" data-count=\"").append(shots.size()).append("\">");
            b.append("<div class=\"frames\">");
            int i = 0;
            for (Screenshot s : shots) {
                b.append("<figure class=\"frame\" data-index=\"").append(i)
                        .append("\"><img src=\"").append(up).append(esc(s.imageFile))
                        .append("\" alt=\"").append(esc(s.stateDescription)).append("\"><figcaption>")
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
        List<Interaction> acts = project.interactions.stream()
                .filter(a -> p.id.equals(a.pageId)).toList();
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
        List<Link> pageLinks = project.links.stream()
                .filter(l -> p.id.equals(l.sourcePageId)).toList();
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

        // Source files (embedded with highlighting, and downloadable).
        if (!p.sourceFiles.isEmpty()) {
            b.append(panelOpen("SOURCE"));
            for (String src : p.sourceFiles) b.append(embeddedSource(src, up));
            b.append(panelClose());
        }

        b.append(notesPanel(EntityType.PAGE, p.id));
        return shell(shortUrl(p.url) + " // page", up, b.toString(), "page");
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
                b.append("</div>");
            }
        }
        b.append(panelClose());

        b.append(notesPanel(EntityType.FORM, f.id));
        return shell("form " + f.id, up, b.toString(), "form");
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
        List<Note> notes = project.notesFor(type, id);
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

    private static void write(Path path, String html) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, html.getBytes(StandardCharsets.UTF_8));
    }
}
