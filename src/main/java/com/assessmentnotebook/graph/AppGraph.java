package com.assessmentnotebook.graph;

import com.assessmentnotebook.model.EntityType;
import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.Project;
import com.assessmentnotebook.model.Relationship;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A read-only view over a {@link Project}'s relationships. It answers the graph
 * questions the documentation needs: what a node points at, what points at it,
 * how a page was discovered, and the path-based application tree.
 *
 * <p>The tree is derived from page URLs (structure), while discovery and link
 * edges (behavior) are surfaced separately, because the application is a graph:
 * a page may sit at one path yet be reachable by several routes.
 */
public final class AppGraph {
    private final Project project;
    // Edge indexes, built on first use so a graph over a large project answers
    // per-entity questions without rescanning every relationship each time.
    private Map<String, List<Relationship>> outIndex;
    private Map<String, List<Relationship>> inIndex;
    private int indexedSize = -1;

    public AppGraph(Project project) {
        this.project = project;
    }

    /** Edges originating at the given entity. */
    public List<Relationship> outgoing(EntityType type, String id) {
        ensureIndex();
        return outIndex.getOrDefault(key(type, id), List.of());
    }

    /** Edges pointing at the given entity. */
    public List<Relationship> incoming(EntityType type, String id) {
        ensureIndex();
        return inIndex.getOrDefault(key(type, id), List.of());
    }

    private void ensureIndex() {
        if (outIndex != null && indexedSize == project.relationships.size()) return;
        outIndex = new HashMap<>();
        inIndex = new HashMap<>();
        for (Relationship r : project.relationships) {
            outIndex.computeIfAbsent(key(r.fromType, r.fromId), k -> new ArrayList<>()).add(r);
            inIndex.computeIfAbsent(key(r.toType, r.toId), k -> new ArrayList<>()).add(r);
        }
        indexedSize = project.relationships.size();
    }

    private static String key(EntityType type, String id) {
        return type + ":" + id;
    }

    /** Outgoing edges of the given entity that carry a particular label. */
    public List<Relationship> outgoing(EntityType type, String id, String kind) {
        List<Relationship> out = new ArrayList<>();
        for (Relationship r : outgoing(type, id)) {
            if (kind.equals(r.kind)) out.add(r);
        }
        return out;
    }

    /**
     * Distinct ways this page was discovered: incoming DISCOVERED and
     * REDIRECTS_TO edges, each described from the source's point of view.
     */
    public List<String> discoveryPaths(String pageId) {
        List<String> out = new ArrayList<>();
        for (Relationship r : incoming(EntityType.PAGE, pageId)) {
            if (Relationship.DISCOVERED.equals(r.kind)
                    || Relationship.REDIRECTS_TO.equals(r.kind)
                    || Relationship.LINKS_TO.equals(r.kind)) {
                Page src = project.findPage(r.fromId);
                String from = src != null ? src.url : r.fromId;
                String how = Relationship.REDIRECTS_TO.equals(r.kind) ? "redirect"
                        : Relationship.LINKS_TO.equals(r.kind) ? "link" : "discovery";
                String detail = (r.detail == null || r.detail.isEmpty()) ? "" : " (" + r.detail + ")";
                out.add(from + " → " + how + detail);
            }
        }
        return out;
    }

    /**
     * Build the path-based application tree. The root is labeled with the
     * project domain (falling back to the host of the first page URL).
     */
    public TreeNode buildTree() {
        String rootLabel = project.target.domain;
        if (rootLabel == null || rootLabel.isEmpty()) {
            rootLabel = firstHost();
        }
        if (rootLabel == null || rootLabel.isEmpty()) {
            rootLabel = "application";
        }
        TreeNode root = new TreeNode(rootLabel, "/");

        for (Page page : project.pages) {
            // A templated path (/api/users/{id}) places the record where the
            // tester thinks of it, not under the one id it was first seen with.
            List<String> segments = page.pathTemplate == null || page.pathTemplate.isBlank()
                    ? pathSegments(page.url) : splitPath(page.pathTemplate);
            TreeNode node = root;
            for (String seg : segments) {
                node = node.child(seg);
            }
            // A page whose path is "/" attaches at the root itself.
            node.addPage(page.id);
        }
        return root;
    }

    // ---- URL parsing -----------------------------------------------------

    /** Split a URL's path into non-empty segments; query and fragment ignored. */
    static List<String> pathSegments(String url) {
        return splitPath(pathOf(url));
    }

    private static List<String> splitPath(String path) {
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (!seg.isEmpty()) out.add(seg);
        }
        return out;
    }

    private static String pathOf(String url) {
        if (url == null) return "/";
        try {
            URI u = URI.create(url.trim());
            String p = u.getPath();
            return (p == null || p.isEmpty()) ? "/" : p;
        } catch (IllegalArgumentException badUrl) {
            // Fall back to a naive split for non-conforming URLs.
            String s = url;
            int scheme = s.indexOf("://");
            if (scheme >= 0) s = s.substring(scheme + 3);
            int slash = s.indexOf('/');
            if (slash < 0) return "/";
            s = s.substring(slash);
            int q = s.indexOf('?');
            if (q >= 0) s = s.substring(0, q);
            int h = s.indexOf('#');
            if (h >= 0) s = s.substring(0, h);
            return s.isEmpty() ? "/" : s;
        }
    }

    private String firstHost() {
        for (Page p : project.pages) {
            try {
                String host = URI.create(p.url.trim()).getHost();
                if (host != null && !host.isEmpty()) return host;
            } catch (RuntimeException ignored) {
                // try next page
            }
        }
        return null;
    }
}
