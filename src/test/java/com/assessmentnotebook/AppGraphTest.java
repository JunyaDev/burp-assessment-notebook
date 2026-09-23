package com.assessmentnotebook;

import com.assessmentnotebook.graph.AppGraph;
import com.assessmentnotebook.graph.TreeNode;
import com.assessmentnotebook.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppGraphTest {

    private Project sample() {
        Project p = new Project();
        p.target.domain = "example.com";
        p.pages.add(page(p, "https://example.com/"));
        p.pages.add(page(p, "https://example.com/login"));
        p.pages.add(page(p, "https://example.com/dashboard"));
        p.pages.add(page(p, "https://example.com/dashboard/profile"));
        p.pages.add(page(p, "https://example.com/api/users"));
        return p;
    }

    private Page page(Project p, String url) {
        Page pg = new Page();
        pg.id = p.nextId(EntityType.PAGE);
        pg.url = url;
        return pg;
    }

    @Test void buildsPathTree() {
        Project p = sample();
        AppGraph g = new AppGraph(p);
        TreeNode root = g.buildTree();
        assertEquals("example.com", root.label);
        assertTrue(root.children.containsKey("dashboard"));
        assertTrue(root.children.containsKey("api"));
        // dashboard/profile nests under dashboard.
        TreeNode dash = root.children.get("dashboard");
        assertTrue(dash.isPage());
        assertTrue(dash.children.containsKey("profile"));
        assertTrue(dash.children.get("profile").isPage());
        // api is an interior node (no page at /api) with a users child.
        TreeNode api = root.children.get("api");
        assertFalse(api.isPage());
        assertTrue(api.children.get("users").isPage());
    }

    @Test void reportsDiscoveryPaths() {
        Project p = sample();
        Page login = p.findPageByRequest("https://example.com/login", "GET");
        Page dash = p.findPageByRequest("https://example.com/dashboard", "GET");

        Relationship r = new Relationship(EntityType.PAGE, login.id,
                Relationship.REDIRECTS_TO, EntityType.PAGE, dash.id);
        r.detail = "302";
        p.relationships.add(r);

        AppGraph g = new AppGraph(p);
        List<String> paths = g.discoveryPaths(dash.id);
        assertEquals(1, paths.size());
        assertTrue(paths.get(0).contains("/login"));
        assertTrue(paths.get(0).contains("redirect"));
        assertTrue(paths.get(0).contains("302"));
    }

    @Test void outgoingAndIncomingEdgesAreDirected() {
        Project p = sample();
        Page a = p.pages.get(0);
        Page b = p.pages.get(1);
        p.relationships.add(new Relationship(EntityType.PAGE, a.id,
                Relationship.LINKS_TO, EntityType.PAGE, b.id));
        AppGraph g = new AppGraph(p);
        assertEquals(1, g.outgoing(EntityType.PAGE, a.id).size());
        assertEquals(0, g.outgoing(EntityType.PAGE, b.id).size());
        assertEquals(1, g.incoming(EntityType.PAGE, b.id).size());
    }
}
