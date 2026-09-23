package com.assessmentnotebook.graph;

import com.assessmentnotebook.model.EntityType;
import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.Project;
import com.assessmentnotebook.model.Relationship;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

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

    public AppGraph(Project project) {
        this.project = project;
    }

    /** Edges originating at the given entity. */
    public List<Relationship> outgoing(EntityType type, String id) {
        List<Relationship> out = new ArrayList<>();
        for (Relationship r : project.relationships) {
            if (r.fromType == type && id.equals(r.fromId)) out.add(r);
        }
        return out;
    }

    /** Edges pointing at the given entity. */
    public List<Relationship> incoming(EntityType type, String id) {
        List<Relationship> out = new ArrayList<>();
        for (Relationship r : project.relationships) {
            if (r.toType == type && id.equals(r.toId)) out.add(r);
        }
        return out;
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
            List<String> segments = pathSegments(page.url);
            TreeNode node = root;
            for (String seg : segments) {
                node = node.child(seg);
            }
            // A page whose path is "/" attaches at the root itself.
            if (segments.isEmpty()) {
                if (root.pageId == null) root.pageId = page.id;
            } else {
                node.pageId = page.id;
            }
        }
        return root;
    }

    // ---- URL parsing -----------------------------------------------------

    /** Split a URL's path into non-empty segments; query and fragment ignored. */
    static List<String> pathSegments(String url) {
        List<String> out = new ArrayList<>();
        String path = pathOf(url);
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
