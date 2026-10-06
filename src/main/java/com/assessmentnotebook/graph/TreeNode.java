package com.assessmentnotebook.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * A node in the application tree. Interior nodes are path segments that may not
 * themselves be registered pages (e.g. {@code /api} above {@code /api/users});
 * a node with a non-null {@link #pageId} corresponds to a documented page.
 */
public class TreeNode {
    /** The path segment this node represents ({@code dashboard}), or the root label. */
    public final String label;
    /** The full path from the root to this node ({@code /dashboard/profile}). */
    public final String fullPath;
    /** Id of the page at this path, if one is registered. */
    public String pageId;
    /**
     * Every page registered at this path, in registration order: one path often
     * answers several methods (GET and POST of the same endpoint).
     */
    public final List<String> pageIds = new ArrayList<>();
    /** Children keyed by segment, kept sorted for stable output. */
    public final TreeMap<String, TreeNode> children = new TreeMap<>();

    public TreeNode(String label, String fullPath) {
        this.label = label;
        this.fullPath = fullPath;
    }

    public TreeNode child(String segment) {
        String childPath = fullPath.equals("/") ? "/" + segment : fullPath + "/" + segment;
        return children.computeIfAbsent(segment, s -> new TreeNode(s, childPath));
    }

    public List<TreeNode> sortedChildren() {
        return new ArrayList<>(children.values());
    }

    public boolean isPage() { return pageId != null; }

    /** Attach a page at this node; the first one attached stays the primary. */
    public void addPage(String id) {
        if (pageId == null) pageId = id;
        if (!pageIds.contains(id)) pageIds.add(id);
    }
}
