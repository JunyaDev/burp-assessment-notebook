package com.assessmentnotebook;

import com.assessmentnotebook.analyze.HtmlAnalyzer;
import com.assessmentnotebook.core.NotebookController;
import com.assessmentnotebook.core.PageRegistration;
import com.assessmentnotebook.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HtmlGeneratorTest {

    private static final String LOGIN_HTML =
            "<html><head><title>Login</title></head><body>"
            + "<form id=login action=/session method=post>"
            + "<input name=username type=text required></form>"
            + "<a href=https://site.test/dashboard>Dashboard</a></body></html>";

    @Test void registerPageGeneratesInterlinkedDocs(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("Gen test");
        c.project().target.domain = "site.test";

        PageRegistration reg = new PageRegistration();
        reg.url = "https://site.test/login";
        reg.method = "GET";
        reg.statusCode = 200;
        reg.contentType = "text/html";
        reg.discoverySource = "proxy history";
        reg.discovered = new HtmlAnalyzer().analyze(LOGIN_HTML, reg.url);
        reg.pageSource = LOGIN_HTML;

        Page page = c.registerPage(reg);

        // Index and the page doc exist and cross-reference.
        Path index = c.layout().indexHtml();
        Path pageDoc = c.layout().pages().resolve(page.id + ".html");
        assertTrue(Files.exists(index));
        assertTrue(Files.exists(pageDoc));
        assertTrue(Files.exists(c.layout().assets().resolve("retro.css")));

        String indexHtml = Files.readString(index);
        assertTrue(indexHtml.contains("site.test"), "domain on overview");
        assertTrue(indexHtml.contains("pages/" + page.id + ".html"), "tree links to page doc");

        String pageHtml = Files.readString(pageDoc);
        assertTrue(pageHtml.contains("/session") || pageHtml.contains("forms/"),
                "page doc references its form");
        assertTrue(pageHtml.contains("Login"), "captured title present");

        // One form and one parameter were created and linked.
        assertEquals(1, c.project().forms.size());
        assertEquals(1, c.project().parameters.size());
        assertFalse(c.project().relationships.isEmpty());
    }

    @Test void secondPageResolvesLinkRelationship(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("Links");

        PageRegistration login = new PageRegistration();
        login.url = "https://site.test/login";
        login.discovered = new HtmlAnalyzer().analyze(LOGIN_HTML, login.url);
        c.registerPage(login);

        // Register the dashboard the login page links to.
        PageRegistration dash = new PageRegistration();
        dash.url = "https://site.test/dashboard";
        c.registerPage(dash);

        // The earlier link should now resolve to the dashboard page doc.
        boolean resolved = c.project().links.stream()
                .anyMatch(l -> "https://site.test/dashboard".equals(l.destinationUrl)
                        && l.destinationPageId != null);
        assertTrue(resolved, "link destination resolved to a registered page");

        boolean linksToEdge = c.project().relationships.stream()
                .anyMatch(r -> Relationship.LINKS_TO.equals(r.kind));
        assertTrue(linksToEdge, "links-to edge created between the two pages");
    }

    @Test void notesAndFindingsRenderAndSurviveRegeneration(@TempDir Path dir) throws IOException {
        NotebookController c = new NotebookController(dir);
        c.create("Notes");
        PageRegistration reg = new PageRegistration();
        reg.url = "https://site.test/login";
        Page page = c.registerPage(reg);

        c.addNote(EntityType.PAGE, page.id, Note.Kind.HYPOTHESIS, "username may be enumerable");
        c.createVulnerability("Reflected XSS in q", Vulnerability.Severity.HIGH,
                "https://site.test/login");

        // Regenerate again; notes and findings persist (they live in the model).
        c.saveAndGenerate();

        String pageHtml = Files.readString(c.layout().pages().resolve(page.id + ".html"));
        assertTrue(pageHtml.contains("username may be enumerable"));
        assertTrue(pageHtml.contains("Hypothesis"));

        Vulnerability v = c.project().vulnerabilities.get(0);
        String vulnHtml = Files.readString(
                c.layout().vulnerabilities().resolve(v.id + ".html"));
        assertTrue(vulnHtml.contains("Reflected XSS in q"));
        assertTrue(vulnHtml.contains("HIGH"));
        // The finding links to the affected page it matched by URL.
        assertTrue(c.project().relationships.stream()
                .anyMatch(r -> Relationship.AFFECTS.equals(r.kind)));
    }
}
