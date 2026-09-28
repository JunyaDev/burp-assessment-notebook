package com.assessmentnotebook;

import com.assessmentnotebook.analyze.DiscoverySourceInference;
import com.assessmentnotebook.analyze.HtmlAnalyzer;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.DiscoverySource;
import com.assessmentnotebook.model.EntityType;
import com.assessmentnotebook.model.Page;
import com.assessmentnotebook.model.Relationship;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiscoverySourceTest {

    @Test void infersLinkFromReferer() {
        DiscoverySourceInference.Result r = DiscoverySourceInference.infer(
                "GET", List.of("Referer: https://s.test/dashboard"), "Proxy");
        assertEquals(DiscoverySource.LINK_FROM_PAGE, r.kind);
        assertTrue(r.detail.contains("dashboard"));
    }

    @Test void infersXhrFromRequestedWith() {
        DiscoverySourceInference.Result r = DiscoverySourceInference.infer(
                "GET", List.of("X-Requested-With: XMLHttpRequest"), "Proxy");
        assertEquals(DiscoverySource.XHR_FETCH, r.kind);
    }

    @Test void infersFormSubmissionFromPost() {
        DiscoverySourceInference.Result r =
                DiscoverySourceInference.infer("POST", List.of(), "Repeater");
        assertEquals(DiscoverySource.FORM_SUBMISSION, r.kind);
    }

    @Test void infersRepeaterAndProxyFromTool() {
        assertEquals(DiscoverySource.BURP_REPEATER,
                DiscoverySourceInference.infer("GET", List.of(), "Repeater").kind);
        assertEquals(DiscoverySource.BURP_HISTORY,
                DiscoverySourceInference.infer("GET", List.of(), "Proxy").kind);
        assertEquals(DiscoverySource.DIRECT_NAVIGATION,
                DiscoverySourceInference.infer("GET", List.of(), null).kind);
    }

    @Test void redirectDiscoveryIsDetectedAcrossPages(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("redir");

        // Register /login whose response 302s to /dashboard.
        PageRegistration login = new PageRegistration();
        login.url = "https://s.test/login";
        login.method = "POST";
        login.statusCode = 302;
        login.contentType = "text/html";
        login.reasonPhrase = "Found";
        login.responseHeaders = List.of("Location: https://s.test/dashboard");
        login.rawRequest = "POST /login HTTP/1.1".getBytes();
        login.rawResponse = "HTTP/1.1 302 Found".getBytes();
        c.registerPage(login);

        // Then register the redirect target.
        PageRegistration dash = new PageRegistration();
        dash.url = "https://s.test/dashboard";
        dash.method = "GET";
        dash.statusCode = 200;
        dash.contentType = "text/html";
        dash.discovered = new HtmlAnalyzer().analyze("<html><title>Dash</title></html>", dash.url);
        Page dashboard = c.registerPage(dash);

        assertEquals(DiscoverySource.HTTP_REDIRECT, dashboard.discoverySourceKind);
        boolean edge = c.project().relationships.stream().anyMatch(r ->
                r.kind.equals(Relationship.REDIRECTS_TO)
                && r.toType == EntityType.PAGE && r.toId.equals(dashboard.id));
        assertTrue(edge, "a redirects-to edge points at the dashboard");
    }
}
